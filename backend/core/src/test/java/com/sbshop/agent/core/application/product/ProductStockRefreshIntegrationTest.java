package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.*;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:stockrefresh;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
	"spring.datasource.password="})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ProductStockRefreshIntegrationTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStockRefreshIntegrationTest {
	@SpringBootApplication
	@EntityScan(basePackages = "com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
	@Import(ProductSyncService.class)
	static class TestApp {}

	@Autowired
	ProductSyncService service;
	@Autowired
	ProductRepository products;
	@Autowired
	PlatformTransactionManager transactions;
	@MockitoBean
	ProductStockCrawlerPort crawler;
	@MockitoBean
	OrderLineItemRepository orders;
	@MockitoBean
	ActionLogService actions;
	final String url = "https://www.iherb.com/pr/example/12345";

	Product create() {
		return new TransactionTemplate(transactions).execute(s -> {
			Product p = Product.create("SB-" + UUID.randomUUID(),
				new ProductCreateCommand(url, new BigDecimal("10000"), "기본명", "Original", "브랜드", "US",
					new BigDecimal("0.3"), BigDecimal.TEN, MeasureUnit.G, List.of(), List.of(), "html", "FOOD",
					true, 3, new BigDecimal("20"), VendorType.IHB, null));
			p.update(ProductUpdateCommand.builder().stock(7).build());
			return products.saveAndFlush(p);
		});
	}

	Product read(Product p) {
		return products.findById(p.getId()).orElseThrow();
	}

	@Test
	void crawlerRunsWithoutTransactionAndUnknownPriceDoesNotEraseExistingValue() {
		Product p = create();
		when(crawler.checkStockWithDetails(url)).thenAnswer(inv -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, null, null);
		});
		var item = service.syncProductStock(p.getId());
		assertThat(item.state()).isEqualTo(ProductSyncService.State.UPDATED);
		Product stored = read(p);
		assertThat(stored.getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(stored.getCostPrice()).isEqualByComparingTo("10000");
		assertThat(stored.getLogisticsInfo().getStock()).isEqualTo(7);
		assertThat(stored.getSalesQuantity()).isEqualTo(300);
	}

	@Test
	void revisionChangedDuringCrawlPreservesNewerData() {
		Product p = create();
		when(crawler.checkStockWithDetails(url)).thenAnswer(inv -> {
			new TransactionTemplate(transactions).executeWithoutResult(s -> {
				Product changed = products.findForEdit(p.getId()).orElseThrow();
				changed.updateCostPrice(new BigDecimal("12345"));
				products.saveAndFlush(changed);
			});
			return new StockCheckResult(StockStatus.OUT_OF_STOCK, BigDecimal.TEN, 0, null);
		});
		assertThat(service.syncProductStock(p.getId()).state()).isEqualTo(ProductSyncService.State.CONFLICT);
		assertThat(read(p).getCostPrice()).isEqualByComparingTo("12345");
		assertThat(read(p).getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
	}

	@Test
	void failedCrawlPreservesValuesAndRecordsFailureInsteadOfSuccess() {
		Product p = create();
		when(crawler.checkStockWithDetails(url)).thenThrow(new IllegalStateException("소싱처 응답 지연"));
		when(orders.findProductIdsByShippingStatus(ShippingStatus.NEW)).thenReturn(List.of(p.getId()));
		when(orders.findProductIdsByShippingStatus(ShippingStatus.PREPARING)).thenReturn(List.of(p.getId()));
		service.syncStockForPreparingOrdersAsync();
		Product stored = read(p);
		assertThat(stored.getCostPrice()).isEqualByComparingTo("10000");
		assertThat(stored.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(stored.getLastCrawlError()).contains("소싱처 응답 지연");
		verify(crawler, times(1)).checkStockWithDetails(url);
		verify(actions, never()).record(any(), any(), eq(ActionStatus.SUCCESS), any());
		verify(actions).record(any(), isNull(), eq(ActionStatus.FAILED), contains("성공 0 / 미반영 1 / 대상 1"));
	}

	@Test
	void malformedAndSourceGoneResponsesNeverTurnProductIntoOutOfStock() {
		Product p = create();
		when(crawler.checkStockWithDetails(url)).thenReturn(null,
			new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null, true),
			new StockCheckResult(StockStatus.IN_STOCK, BigDecimal.ONE.negate(), 10, null));
		for (int i = 0; i < 3; i++) {
			assertThat(service.syncProductStock(p.getId()).state()).isEqualTo(ProductSyncService.State.FAILED);
			assertThat(read(p).getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
			assertThat(read(p).getCostPrice()).isEqualByComparingTo("10000");
		}
	}

	@Test
	void missingAndDeletedProductsAreNotCountedAsSuccessfullyCrawled() {
		Product p = create();
		new TransactionTemplate(transactions).executeWithoutResult(s -> {
			Product stored = products.findForEdit(p.getId()).orElseThrow();
			stored.markDeleted();
			products.saveAndFlush(stored);
		});
		var result = service.syncStockForPreparingOrders(List.of(p.getId(), -1L));
		assertThat(result.complete()).isFalse();
		assertThat(result.updatedCount()).isZero();
		assertThat(result.items()).allMatch(i -> i.state() == ProductSyncService.State.SKIPPED);
		verifyNoInteractions(crawler);
	}

	@Test
	void interruptedBatchReportsUnprocessedItemsAndKeepsInterruptFlag() {
		try {
			Thread.currentThread().interrupt();
			var result = service.syncStockForPreparingOrders(List.of(1L, 2L));
			assertThat(result.complete()).isFalse();
			assertThat(result.items()).allMatch(i -> i.state() == ProductSyncService.State.CANCELLED);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			verifyNoInteractions(crawler);
			verify(actions).record(any(), isNull(), eq(ActionStatus.WARNING), contains("상품 1 · CANCELLED"));
			verify(actions).record(any(), isNull(), eq(ActionStatus.WARNING), contains("상품 2 · CANCELLED"));
		} finally {
			Thread.interrupted();
		}
	}
}

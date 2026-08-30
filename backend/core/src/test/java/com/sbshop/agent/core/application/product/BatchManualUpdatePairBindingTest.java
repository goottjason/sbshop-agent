package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchManualUpdatePairBindingTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private StockCrawlerRouter stockCrawlerRouter;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private MarginCalculator marginCalculator;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ProductMarketSyncService productMarketSyncService;
	@Mock
	private MarketFeeService marketFeeService;

	@Mock
	private Product product1;
	@Mock
	private Product product2;

	private BatchPriceStockService service;

	private static final Long PID_1 = 1L;
	private static final Long PID_2 = 2L;

	@Mock
	private VendorPricePolicyService vendorPricePolicyService;

	@BeforeEach
	void setUp() {
		service = new BatchPriceStockService(productReader, productWriter, productRepository,
			stockCrawlerRouter, processStatusService, marginCalculator, eventPublisher,
			productMarketSyncService, marketFeeService, vendorPricePolicyService);

		lenient().when(vendorPricePolicyService.find(any())).thenReturn(java.util.Optional.of(
			com.sbshop.agent.core.domain.pricing.VendorPricePolicy.builder()
				.shipBaseAmount(java.math.BigDecimal.ZERO).build()));
		lenient().when(productReader.findById(PID_1)).thenReturn(Optional.of(product1));
		lenient().when(productReader.findById(PID_2)).thenReturn(Optional.of(product2));
		lenient().when(product1.getSbCode()).thenReturn("SB-001");
		lenient().when(product2.getSbCode()).thenReturn("SB-002");
		lenient().when(product1.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		lenient().when(product2.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		lenient().when(product1.getSalePrice()).thenReturn(new BigDecimal("1000"));
		lenient().when(product2.getSalePrice()).thenReturn(new BigDecimal("2000"));
		lenient().when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class)))
			.thenReturn(new MarketRepublishResult(List.of(), List.of(), new LinkedHashMap<>()));
	}

	@Test
	@DisplayName("items 순서를 섞어도 각 상품에 자기 price/stock이 적용된다 (엉뚱한 상품에 안 감)")
	void shuffledItems_eachProductGetsItsOwnValue() {
		BigDecimal price1 = new BigDecimal("1500");
		BigDecimal price2 = new BigDecimal("2500");

		service.manualUpdatePriceStock("batch-manual", List.of(
			new PriceStockItem(PID_2, price2, 0),
			new PriceStockItem(PID_1, price1, 5)));

		verify(product1).update(argThat(cmd -> price1.equals(cmd.salePrice())));
		verify(product1).updateStockStatus(StockStatus.IN_STOCK);
		verify(productMarketSyncService).syncPriceStock(eq(PID_1), eq(1500), eq(StockStatus.IN_STOCK));

		verify(product2).update(argThat(cmd -> price2.equals(cmd.salePrice())));
		verify(product2).updateStockStatus(StockStatus.OUT_OF_STOCK);
		verify(productMarketSyncService).syncPriceStock(eq(PID_2), eq(2500), eq(StockStatus.OUT_OF_STOCK));
	}

	@Test
	@DisplayName("item의 price/stock이 null이면 해당 항목만 부분수정한다 (기존 동작 보존)")
	void nullFields_partialUpdatePreserved() {
		lenient().when(product1.getSalePrice()).thenReturn(new BigDecimal("1000"));

		service.manualUpdatePriceStock("batch-manual", List.of(
			new PriceStockItem(PID_1, new BigDecimal("1234"), null)));

		verify(product1).update(argThat(cmd -> new BigDecimal("1234").equals(cmd.salePrice())));
		verify(product1).updateStockStatus(StockStatus.IN_STOCK);
	}
}

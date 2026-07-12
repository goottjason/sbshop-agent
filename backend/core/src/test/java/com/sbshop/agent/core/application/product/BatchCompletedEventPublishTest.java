package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * SP-F Task 1 — BatchCompletedEvent actionType + failCount success 판정 검증.
 */
class BatchCompletedEventPublishTest {

	private ProductReader productReader;
	private ProductWriter productWriter;
	private ApplicationEventPublisher eventPublisher;
	private BatchPriceStockService service;

	@BeforeEach
	void setUp() {
		productReader = mock(ProductReader.class);
		productWriter = mock(ProductWriter.class);
		eventPublisher = mock(ApplicationEventPublisher.class);

		service = new BatchPriceStockService(
			productReader,
			productWriter,
			mock(ProductRepository.class),
			mock(ProductStockCrawlerPort.class),
			mock(ProcessStatusService.class),
			mock(MarginCalculator.class),
			eventPublisher,
			mock(ProductMarketSyncService.class)
		);
	}

	@Test
	void manualUpdateAllFields_실패항목있으면_success_false_actionType_BATCH_MANUAL_UPDATE_ALL() {
		// Arrange: product 1L → 존재, product 2L → empty (→ IllegalArgumentException → per-item catch)
		Product presentProduct = mock(Product.class);
		when(presentProduct.getSbCode()).thenReturn("SB-001");
		when(productReader.findById(1L)).thenReturn(Optional.of(presentProduct));
		when(productReader.findById(2L)).thenReturn(Optional.empty());

		List<Long> productIds = List.of(1L, 2L);
		ProductUpdateCommand cmd = new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, null,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
		List<ProductUpdateCommand> commands = List.of(cmd, cmd);

		ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);

		// Act
		service.manualUpdateAllFields("B-1", productIds, commands);

		// Assert
		org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
		BatchCompletedEvent event = (BatchCompletedEvent) captor.getValue();
		assertThat(event.getActionType()).isEqualTo(ActionLogConstants.BATCH_MANUAL_UPDATE_ALL);
		assertThat(event.isSuccess()).isFalse();
	}

	@Test
	void crawlAndUpdatePriceStock_소싱URL없는항목_failCount증가로_success_false() {
		// Arrange: product with null sourcingUrl → markFailed+skip path → failCount++
		Product product = mock(Product.class);
		when(product.getSbCode()).thenReturn("SB-010");
		when(product.getSourcingUrl()).thenReturn(null);
		when(productReader.findById(10L)).thenReturn(Optional.of(product));

		ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);

		// Act — @Async bypassed in plain Mockito (no Spring context)
		service.crawlAndUpdatePriceStock("B-URL", List.of(10L),
			new java.math.BigDecimal("15"), new java.math.BigDecimal("20"),
			new java.math.BigDecimal("5000"), ActionLogConstants.BATCH_CRAWL_UPDATE);

		// Assert: failCount > 0 → success=false
		org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
		BatchCompletedEvent event = (BatchCompletedEvent) captor.getValue();
		assertThat(event.getActionType()).isEqualTo(ActionLogConstants.BATCH_CRAWL_UPDATE);
		assertThat(event.isSuccess()).isFalse();
	}

	@Test
	void crawlAndUpdatePriceStock_B4경로_actionType_BATCH_BY_SUPPLIER() {
		// Arrange: empty product list → no items processed → failCount=0 → success=true
		// Verify that the actionType passed in is threaded through to the event
		ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);

		// Act — empty list, passes BATCH_BY_SUPPLIER
		service.crawlAndUpdatePriceStock("B-B4", List.of(),
			new java.math.BigDecimal("15"), new java.math.BigDecimal("20"),
			new java.math.BigDecimal("5000"), ActionLogConstants.BATCH_BY_SUPPLIER);

		// Assert: event.getActionType() == BATCH_BY_SUPPLIER
		org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
		BatchCompletedEvent event = (BatchCompletedEvent) captor.getValue();
		assertThat(event.getActionType()).isEqualTo(ActionLogConstants.BATCH_BY_SUPPLIER);
		assertThat(event.isSuccess()).isTrue();
	}

	@Test
	void manualUpdateAllFields_전량성공이면_success_true() {
		// Arrange: both products present
		Product product1 = mock(Product.class);
		when(product1.getSbCode()).thenReturn("SB-001");
		Product product2 = mock(Product.class);
		when(product2.getSbCode()).thenReturn("SB-002");
		when(productReader.findById(1L)).thenReturn(Optional.of(product1));
		when(productReader.findById(2L)).thenReturn(Optional.of(product2));

		List<Long> productIds = List.of(1L, 2L);
		ProductUpdateCommand cmd = new ProductUpdateCommand(
			null, null, null, null, null,
			null, null, null, null, null,
			null, null, null,
			null, null, null,
			null, null, null, null, null,
			null, null, null, null, null);
		List<ProductUpdateCommand> commands = List.of(cmd, cmd);

		ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);

		// Act
		service.manualUpdateAllFields("B-2", productIds, commands);

		// Assert
		org.mockito.Mockito.verify(eventPublisher).publishEvent(captor.capture());
		BatchCompletedEvent event = (BatchCompletedEvent) captor.getValue();
		assertThat(event.getActionType()).isEqualTo(ActionLogConstants.BATCH_MANUAL_UPDATE_ALL);
		assertThat(event.isSuccess()).isTrue();
	}
}

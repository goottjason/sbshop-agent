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

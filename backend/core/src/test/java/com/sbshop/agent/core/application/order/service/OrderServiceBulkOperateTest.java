package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.BulkConfirmResult;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderServiceBulkOperateTest {
	private OrderRepository orderRepository;
	private OrderLineItemRepository orderLineItemRepository;
	private ShipmentRepository shipmentRepository;

	@BeforeEach
	void setUp() {
		orderRepository = mock(OrderRepository.class);
		orderLineItemRepository = mock(OrderLineItemRepository.class);
		shipmentRepository = mock(ShipmentRepository.class);
		credentialRepository = mock(MarketCredentialRepository.class);
		marketplaceShippingService = mock(MarketplaceShippingService.class);
		service = new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService, shippingWriter());
	}

	private MarketCredentialRepository credentialRepository;
	private MarketplaceShippingService marketplaceShippingService;
	private OrderService service;

	@Test
	@DisplayName("bulkConfirmOrders: 성공/실패 혼합 시 카운트·실패ID·에러 메시지를 정확히 집계한다")
	void bulkConfirmMixed() {
		stubConfirmableOrder(1L);
		when(orderRepository.findById(2L)).thenReturn(Optional.empty());
		stubConfirmableOrder(3L);

		BulkConfirmResult result = service.bulkConfirmOrders(List.of(1L, 2L, 3L));

		assertThat(result.getSuccessCount()).isEqualTo(2);
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getFailedIds()).containsExactly(2L);
		assertThat(result.getErrors()).containsExactly("Order 2: Order not found: 2");
	}

	@Test
	@DisplayName("bulkConfirmOrders: 전부 성공하면 errors는 null이다")
	void bulkConfirmAllSuccess() {
		stubConfirmableOrder(1L);
		stubConfirmableOrder(2L);

		BulkConfirmResult result = service.bulkConfirmOrders(List.of(1L, 2L));

		assertThat(result.getSuccessCount()).isEqualTo(2);
		assertThat(result.getFailedCount()).isZero();
		assertThat(result.getFailedIds()).isEmpty();
		assertThat(result.getErrors()).isNull();
	}

	@Test
	@DisplayName("bulkCancelOrders: 성공/실패 혼합 시 카운트·실패ID·에러 메시지를 정확히 집계한다")
	void bulkCancelMixed() {
		stubCancelableOrder(1L);
		when(orderRepository.findById(2L)).thenReturn(Optional.empty());
		stubCancelableOrder(3L);

		BulkConfirmResult result = service.bulkCancelOrders(List.of(1L, 2L, 3L));

		assertThat(result.getSuccessCount()).isEqualTo(2);
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getFailedIds()).containsExactly(2L);
		assertThat(result.getErrors()).containsExactly("Order 2: Order not found: 2");
	}

	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	private void stubConfirmableOrder(Long id) {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-" + id).build();
		when(orderRepository.findById(id)).thenReturn(Optional.of(order));
		OrderLineItem item = OrderLineItem.builder()
			.orderId(id)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.NEW).build())
			.build();
		when(orderLineItemRepository.findByOrderId(id)).thenReturn(List.of(item));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(MarketCredential.builder().marketType(MarketType.COUPANG).build()));
		MarketOrderPort port = mock(MarketOrderPort.class);
		when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);
	}

	private void stubCancelableOrder(Long id) {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-" + id).build();
		when(orderRepository.findById(id)).thenReturn(Optional.of(order));
		OrderLineItem item = OrderLineItem.builder()
			.orderId(id)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.NEW).build())
			.build();
		when(orderLineItemRepository.findByOrderId(id)).thenReturn(List.of(item));
	}
}

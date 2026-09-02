package com.sbshop.agent.core.application.order.service;

import org.assertj.core.api.Assertions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.exception.MarketOrderAcceptException;
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

class OrderServiceConfirmFailureTypeTest {
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
			credentialRepository, marketplaceShippingService, shippingWriter(), orderMarketRefresher());
	}

	private MarketCredentialRepository credentialRepository;
	private MarketplaceShippingService marketplaceShippingService;
	private OrderService service;

	@Test
	@DisplayName("접수 API 실패 시 전용 예외로 원인(cause)과 원 메시지를 보존해 표면화한다")
	void confirmOrder_acceptApiFailure_preservesCauseAndType() {
		Long id = 1L;
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-1").build();
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
		IllegalStateException rootCause = new IllegalStateException("마켓 접수 거부: 재고 없음");
		doThrow(rootCause).when(port).acceptOrders(any(), any());

		assertThatThrownBy(() -> service.confirmOrder(id))
			.isInstanceOf(MarketOrderAcceptException.class)
			.hasMessageContaining("마켓 접수 거부: 재고 없음")
			.cause().isSameAs(rootCause);
	}

	@Test
	@DisplayName("접수 실패 예외의 원인 유형은 소실되지 않는다(원 예외를 cause로 복원 가능)")
	void confirmOrder_acceptApiFailure_rootTypeRecoverable() {
		Long id = 2L;
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-2").build();
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
		IllegalArgumentException rootCause = new IllegalArgumentException("잘못된 마켓 주문번호");
		doThrow(rootCause).when(port).acceptOrders(any(), any());

		Throwable thrown = Assertions.catchThrowable(() -> service.confirmOrder(id));

		assertThat(thrown).isInstanceOf(MarketOrderAcceptException.class);
		assertThat(thrown.getCause()).isInstanceOf(IllegalArgumentException.class);
		assertThat(thrown.getCause().getMessage()).isEqualTo("잘못된 마켓 주문번호");
	}

	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}
	private com.sbshop.agent.core.application.order.service.OrderMarketRefresher orderMarketRefresher() {
		return org.mockito.Mockito.mock(
			com.sbshop.agent.core.application.order.service.OrderMarketRefresher.class);
	}

}

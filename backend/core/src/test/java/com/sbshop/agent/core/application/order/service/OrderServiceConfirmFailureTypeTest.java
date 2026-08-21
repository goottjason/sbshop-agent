package com.sbshop.agent.core.application.order.service;

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

/**
 * F-ORD-8: 마켓 접수(발주확인) 실패를 밋밋한 RuntimeException으로 뭉개 원인 유형·사유가 소실되던 문제.
 * 접수 API가 던진 원인 예외를 유형 보존(전용 예외)과 cause 체이닝으로 그대로 표면화하는지 고정한다.
 */
class OrderServiceConfirmFailureTypeTest {

	private OrderRepository orderRepository;
	private OrderLineItemRepository orderLineItemRepository;
	private ShipmentRepository shipmentRepository;

	/**
	 * D-133: 송장 쓰기 통로는 <b>진짜 객체</b>를 끼운다. 목으로 대체하면 라인아이템 쓰기 자체가
	 * 사라져 기존 검증이 통과해도 아무것도 증명하지 못한다. {@code shipment_id}가 null인 이
	 * 테스트들에서는 통로가 배송을 건드리지 않으므로 종전과 동작이 같다 — 그 사실이 회귀 증거다.
	 */
	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	private MarketCredentialRepository credentialRepository;
	private MarketplaceShippingService marketplaceShippingService;
	private OrderService service;

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
		// 접수 API가 유형이 뚜렷한 원인 예외를 던지는 상황을 재현한다.
		IllegalStateException rootCause = new IllegalStateException("마켓 접수 거부: 재고 없음");
		doThrow(rootCause).when(port).acceptOrders(any(), any());

		assertThatThrownBy(() -> service.confirmOrder(id))
			// 유형 보존: 밋밋한 RuntimeException이 아니라 전용 예외여야 한다.
			.isInstanceOf(MarketOrderAcceptException.class)
			// 원 메시지 보존: 실패 사유가 메시지에 남아야 한다.
			.hasMessageContaining("마켓 접수 거부: 재고 없음")
			// 원인 예외 보존: cause 체이닝으로 원 예외 인스턴스를 그대로 잡을 수 있어야 한다.
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

		Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> service.confirmOrder(id));

		assertThat(thrown).isInstanceOf(MarketOrderAcceptException.class);
		assertThat(thrown.getCause()).isInstanceOf(IllegalArgumentException.class);
		assertThat(thrown.getCause().getMessage()).isEqualTo("잘못된 마켓 주문번호");
	}
}

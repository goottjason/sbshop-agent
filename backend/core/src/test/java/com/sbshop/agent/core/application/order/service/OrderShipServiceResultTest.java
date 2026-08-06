package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import org.mockito.ArgumentCaptor;

/**
 * F-ORD-30 / SP-3: 주문 1건 발송이 마켓 shipOrder 실패를 삼키지 않고 결과({@link OrderShipOutcome})로
 * 표면화해야 한다. F-ORD-31로 이 로직이 {@link OrderShipProcessor}로 이동했으므로 여기서 검증한다(로직 불변).
 */
@ExtendWith(MockitoExtension.class)
class OrderShipServiceResultTest {

	@Mock private OrderRepository orderRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ShipmentRepository shipmentRepository;

	/**
	 * D-133: 송장 쓰기 통로는 <b>진짜 객체</b>를 끼운다. 목으로 대체하면 라인아이템 쓰기 자체가
	 * 사라져 기존 검증이 통과해도 아무것도 증명하지 못한다. {@code shipment_id}가 null인 이
	 * 테스트들에서는 통로가 배송을 건드리지 않으므로 종전과 동작이 같다 — 그 사실이 회귀 증거다.
	 */
	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}
	@Mock private MarketplaceShippingService marketplaceShippingService;
	@Mock private MarketOrderPort port;

	private OrderShipProcessor processor() {
		return new OrderShipProcessor(orderRepository, credentialRepository,
			orderLineItemRepository, marketplaceShippingService, shippingWriter());
	}

	private Order order(Long id) {
		return Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-" + id).build();
	}

	private OrderLineItem shippableItem() {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.PREPARING)
				.trackingNo("TRK-1")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.build())
			.build();
	}

	private void stubCoupang() {
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(MarketCredential.builder().marketType(MarketType.COUPANG).build()));
	}

	@Test
	@DisplayName("마켓 전송 실패 시 FAILED 결과로 표면화되고 사유가 담긴다")
	void marketFailure_returnsFailedOutcome() {
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L)));
		stubCoupang();
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(shippableItem()));
		when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);
		doThrow(new RuntimeException("마켓 전송 거부"))
			.when(port).shipOrder(any(), any(), any(), anyString(), any());

		OrderShipOutcome outcome = processor().shipSingleOrder(1L);

		assertThat(outcome.isFailed()).isTrue();
		assertThat(outcome.getErrorMessage()).contains("마켓 전송 거부");
	}

	@Test
	@DisplayName("발송 성공 시 SHIPPED 결과를 반환하고 저장된 상태가 DISPATCHED다")
	void success_returnsShippedOutcome() {
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L)));
		stubCoupang();
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(shippableItem()));
		when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);

		OrderShipOutcome outcome = processor().shipSingleOrder(1L);

		assertThat(outcome.isShipped()).isTrue();
		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository).save(captor.capture());
		assertThat(captor.getValue().getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DISPATCHED);
	}

	@Test
	@DisplayName("이미 발송/송장없음 등 정상 스킵은 SKIPPED 결과로 반환한다(실패 아님)")
	void skippedLines_returnSkippedOutcome() {
		Order order = order(3L);
		when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
		stubCoupang();
		OrderLineItem already = OrderLineItem.builder()
			.orderId(3L).quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingNo("TRK-9")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.build())
			.build();
		when(orderLineItemRepository.findByOrderId(3L)).thenReturn(List.of(already));
		lenient().when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);

		OrderShipOutcome outcome = processor().shipSingleOrder(3L);

		assertThat(outcome.isSkipped()).isTrue();
	}

	@Test
	@DisplayName("주문 없음은 FAILED로 표면화된다(F-ORD-30)")
	void orderNotFound_returnsFailedOutcome() {
		when(orderRepository.findById(9L)).thenReturn(Optional.empty());

		OrderShipOutcome outcome = processor().shipSingleOrder(9L);

		assertThat(outcome.isFailed()).isTrue();
		assertThat(outcome.getErrorMessage()).contains("주문 없음");
	}
}

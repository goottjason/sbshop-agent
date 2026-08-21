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

/**
 * F-ORD-18: bulkConfirmOrders / bulkCancelOrders 의 일괄 처리 루프(성공 카운트·실패ID 수집·
 * 에러 메시지 포맷·건별 실패시 배치 지속)를 공통 헬퍼로 추출하기 전/후의 동작을 고정하는 특성화 테스트.
 * 동작·응답·메시지 불변 보증용.
 *
 * <p>Mockito 엄격 스터빙(MockitoExtension) 대신 plain mock을 쓴다 — 한 테스트에서 여러 id에
 * 대한 confirm/cancel 해피패스를 세팅하면 strict stubbing이 오탐(PotentialStubbingProblem)을 낸다.
 */
class OrderServiceBulkOperateTest {

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

	/** confirmOrder가 성공하도록 유효한 NEW 주문 픽스처를 세팅한다. */
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

	/** cancelOrder가 성공하도록 NEW 상태(비-GMARKET/AUCTION) 주문 픽스처를 세팅한다. */
	private void stubCancelableOrder(Long id) {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("MO-" + id).build();
		when(orderRepository.findById(id)).thenReturn(Optional.of(order));
		OrderLineItem item = OrderLineItem.builder()
			.orderId(id)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.NEW).build())
			.build();
		when(orderLineItemRepository.findByOrderId(id)).thenReturn(List.of(item));
	}

	@Test
	@DisplayName("bulkConfirmOrders: 성공/실패 혼합 시 카운트·실패ID·에러 메시지를 정확히 집계한다")
	void bulkConfirmMixed() {
		stubConfirmableOrder(1L);
		// id=2는 not found → IllegalArgumentException("Order not found: 2")
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
}

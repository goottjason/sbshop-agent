package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.application.order.probe.MarketOrderProbeRouter;
import com.sbshop.agent.core.application.order.probe.OrderProbeResult;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

class OrderReconciliationServiceTest {

	private OrderRepository orderRepository;
	private OrderLineItemRepository lineItemRepository;
	private MarketOrderProbeRouter probeRouter;
	private OrderReconciliationService service;

	private static final LocalDate FROM = LocalDate.of(2026, 5, 4);
	private static final LocalDate TO = LocalDate.of(2026, 9, 1);

	@BeforeEach
	void setUp() {
		orderRepository = mock(OrderRepository.class);
		lineItemRepository = mock(OrderLineItemRepository.class);
		probeRouter = mock(MarketOrderProbeRouter.class);
		when(probeRouter.has(any())).thenReturn(true);
		service = new OrderReconciliationService(orderRepository, lineItemRepository, probeRouter, 0);
	}

	private Order order(String no, LocalDate date) {
		Order o = mock(Order.class);
		when(o.getMarketOrderNo()).thenReturn(no);
		when(o.getOrderDate()).thenReturn(date.atStartOfDay());
		when(o.getId()).thenReturn(1L);
		return o;
	}

	private OrderLineItem item(ShippingStatus current) {
		OrderLineItem li = mock(OrderLineItem.class);
		when(li.getShippingData()).thenReturn(ShippingData.builder().shippingStatus(current).build());
		return li;
	}

	@Test
	@DisplayName("목록에서 이미 본 주문은 프로브하지 않는다")
	void skipsSeenOrders() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));

		int changed = service.reconcile(MarketType.COUPANG, FROM, TO, Set.of("ORD-1"));

		assertThat(changed).isZero();
		verify(probeRouter, never()).probe(any(), any());
	}

	@Test
	@DisplayName("창 밖 주문은 프로브하지 않는다")
	void skipsOutsideWindow() {
		Order o = order("ORD-1", LocalDate.of(2026, 1, 1));
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));

		service.reconcile(MarketType.COUPANG, FROM, TO, Set.of());

		verify(probeRouter, never()).probe(any(), any());
	}

	@Test
	@DisplayName("FOUND 로 다른 상태가 오면 라인아이템에 반영한다 — 배송완료 뒤라도 반영한다")
	void appliesFoundStatus() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(eq(MarketType.COUPANG), any(Order.class)))
			.thenReturn(OrderProbeResult.found(ShippingStatus.DELIVERED));

		int changed = service.reconcile(MarketType.COUPANG, FROM, TO, Set.of());

		assertThat(changed).isEqualTo(1);
		verify(lineItemRepository).save(li);
	}

	@Test
	@DisplayName("이미 같은 상태면 저장하지 않는다")
	void noWriteWhenSame() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));
		OrderLineItem li = item(ShippingStatus.DELIVERED);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.found(ShippingStatus.DELIVERED));

		assertThat(service.reconcile(MarketType.COUPANG, FROM, TO, Set.of())).isZero();
		verify(lineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("TERMINATED 인데 상태가 비면 아무 것도 바꾸지 않는다 — 쿠팡은 취소/반품을 구분해주지 않는다")
	void terminatedWithoutStatusDoesNothing() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any()))
			.thenReturn(OrderProbeResult.terminated(null, "해당 주문이 취소 또는 반품 되었습니다."));

		assertThat(service.reconcile(MarketType.COUPANG, FROM, TO, Set.of())).isZero();
		verify(lineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("TERMINATED + RETURNED 는 반영한다 — 카페24는 반품을 확정해준다")
	void terminatedWithStatusApplies() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(orderRepository.findByMarketType(MarketType.GMARKET))
			.thenReturn(List.of(o));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any()))
			.thenReturn(OrderProbeResult.terminated(ShippingStatus.RETURNED, "T"));

		assertThat(service.reconcile(MarketType.GMARKET, FROM, TO, Set.of())).isEqualTo(1);
		verify(lineItemRepository).save(li);
	}

	@Test
	@DisplayName("NOT_FOUND 와 UNKNOWN 은 상태를 건드리지 않는다")
	void notFoundAndUnknownDoNothing() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.notFound("유효하지 않은 주문번호 입니다."));

		assertThat(service.reconcile(MarketType.COUPANG, FROM, TO, Set.of())).isZero();

		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.unknown("timeout"));

		assertThat(service.reconcile(MarketType.COUPANG, FROM, TO, Set.of())).isZero();
		verify(lineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("프로브가 없는 마켓은 조회조차 하지 않는다")
	void skipsMarketWithoutProbe() {
		when(probeRouter.has(MarketType.SMART_STORE)).thenReturn(false);

		assertThat(service.reconcile(MarketType.SMART_STORE, FROM, TO, Set.of())).isZero();
		verify(orderRepository, never()).findByMarketType(MarketType.SMART_STORE);
	}

	@Test
	@DisplayName("프로브가 없으면 지연도 걸리지 않는다")
	void noDelayWhenNoProbe() {
		when(probeRouter.has(MarketType.SMART_STORE)).thenReturn(false);

		assertThat(service.reconcile(MarketType.SMART_STORE, FROM, TO, Set.of())).isZero();
	}
}

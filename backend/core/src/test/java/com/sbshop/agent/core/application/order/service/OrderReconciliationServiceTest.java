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
import com.sbshop.agent.core.domain.order.enums.OrderProbeStatus;
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
	@DisplayName("확인하지 못한 주문에도 프로브 결과를 남긴다 — 조용히 넘어가면 사람이 볼 수 없다")
	void recordsProbeResultOnMiss() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any()))
			.thenReturn(OrderProbeResult.notFound("유효하지 않은 주문번호 입니다."));

		service.reconcile(MarketType.COUPANG, FROM, TO, Set.of());

		verify(o).recordProbeResult(OrderProbeStatus.NOT_FOUND);
		verify(orderRepository).save(o);
	}

	@Test
	@DisplayName("확증된 주문에도 프로브 결과를 남긴다 — 마지막으로 언제 확인됐는지가 정보다")
	void recordsProbeResultOnSuccess() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(o));
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any()))
			.thenReturn(OrderProbeResult.found(ShippingStatus.DELIVERED));

		service.reconcile(MarketType.COUPANG, FROM, TO, Set.of());

		verify(o).recordProbeResult(OrderProbeStatus.FOUND);
	}

	@Test
	@DisplayName("단건 재조회는 마켓 값으로 갱신하고 몇 건 바뀌었는지 돌려준다 — 명령 직후 공백을 메운다")
	void reconcileOneAppliesMarketValue() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.NEW);
		when(o.getMarketType()).thenReturn(MarketType.COUPANG);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(eq(MarketType.COUPANG), any(Order.class)))
			.thenReturn(OrderProbeResult.found(ShippingStatus.PREPARING));

		assertThat(service.reconcileOne(o)).isEqualTo(1);
		verify(lineItemRepository).save(li);
		verify(o).recordProbeResult(OrderProbeStatus.FOUND);
	}

	@Test
	@DisplayName("단건 재조회도 확증되지 않으면 상태를 건드리지 않는다 — 명령했다고 짐작하지 않는다")
	void reconcileOneDoesNotGuess() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.NEW);
		when(o.getMarketType()).thenReturn(MarketType.COUPANG);
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.unknown("timeout"));

		assertThat(service.reconcileOne(o)).isZero();
		verify(lineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("프로브가 없는 마켓이면 단건 재조회는 조용히 넘어간다 — 스마트스토어는 목록이 담당한다")
	void reconcileOneSkipsMarketWithoutProbe() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		when(o.getMarketType()).thenReturn(MarketType.SMART_STORE);
		when(probeRouter.has(MarketType.SMART_STORE)).thenReturn(false);

		assertThat(service.reconcileOne(o)).isZero();
		verify(probeRouter, never()).probe(any(), any());
	}

	@Test
	@DisplayName("프로브가 없는 마켓은 조회조차 하지 않는다")
	void skipsMarketWithoutProbe() {
		when(probeRouter.has(MarketType.SMART_STORE)).thenReturn(false);

		assertThat(service.reconcile(MarketType.SMART_STORE, FROM, TO, Set.of())).isZero();
		verify(orderRepository, never()).findByMarketType(MarketType.SMART_STORE);
	}
	@Test
	@DisplayName("확증이 클레임도 반영한다 — 목록이 닿지 않는 주문은 여기서만 갱신된다")
	void reconcileAppliesClaim() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.GMARKET)).thenReturn(List.of(o));
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.found(
			ShippingStatus.SHIPPED,
			com.sbshop.agent.core.domain.order.vo.ClaimData.builder()
				.claimType(com.sbshop.agent.core.domain.order.enums.ClaimType.EXCHANGE)
				.claimStage(com.sbshop.agent.core.domain.order.enums.ClaimStage.REQUESTED)
				.claimRawCode("E00").build(),
			null));

		service.reconcile(MarketType.GMARKET, FROM, TO, Set.of());

		verify(li).applyClaim(any(com.sbshop.agent.core.domain.order.vo.ClaimData.class));
	}

	@Test
	@DisplayName("배송 단계를 알 수 없어도 클레임은 반영한다 — 11번가처럼 클레임 전용 행엔 배송 신호가 없다(D-270)")
	void reconcileAppliesClaimEvenWithoutResolvableShippingStatus() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(o));
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.found(
			ShippingStatus.UNKNOWN,
			com.sbshop.agent.core.domain.order.vo.ClaimData.builder()
				.claimType(com.sbshop.agent.core.domain.order.enums.ClaimType.CANCEL)
				.claimStage(com.sbshop.agent.core.domain.order.enums.ClaimStage.DONE)
				.claimRawCode("취소완료").build(),
			null));

		service.reconcile(MarketType.ELEVEN_STREET, FROM, TO, Set.of());

		verify(li).applyClaim(any(com.sbshop.agent.core.domain.order.vo.ClaimData.class));
		verify(lineItemRepository).save(li);
	}

	@Test
	@DisplayName("클레임이 없으면 기존 클레임을 지우지 않는다 — 부분 응답으로 이력을 날리지 않는다")
	void reconcileKeepsClaimWhenAbsent() {
		Order o = order("ORD-1", LocalDate.of(2026, 7, 1));
		OrderLineItem li = item(ShippingStatus.SHIPPED);
		when(orderRepository.findByMarketType(MarketType.GMARKET)).thenReturn(List.of(o));
		when(lineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(probeRouter.probe(any(), any())).thenReturn(OrderProbeResult.found(ShippingStatus.DELIVERED));

		service.reconcile(MarketType.GMARKET, FROM, TO, Set.of());

		verify(li, never()).applyClaim(any());
	}

}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * 6단계 전제: <b>배송에 속하지 않은 라인아이템</b>을 없앤다.
 *
 * <p>3계층 전환은 각 마켓의 조회 창(30일) 안에서만 일어난다. 창 밖의 옛 주문은 마켓이 더는
 * 내려주지 않아 동기화가 만나지 못하고, 그 라인아이템은 {@code shipment_id}가 비어 있다
 * (2026-08-06 실측 127건). 미러 컬럼과 {@code sb_order.shipment_box_id}를 제거하려면
 * <b>모든 라인아이템이 배송을 가져야</b> 한다 — 그 전에는 옛 행의 송장이 갈 곳을 잃는다.
 *
 * <p>송장의 원본이 뒤집힌다: 종전에는 라인아이템이 원본이고 배송이 미러였는데, 여기서
 * <b>라인아이템의 값을 배송으로 승격</b>시킨다. 미러 컬럼은 그대로 둔다(소비처 이관 전).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyShipmentBackfillServiceTest {

	@Mock
	private OrderLineItemRepository lineItemRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	private LegacyShipmentBackfillService service;

	private final List<Shipment> savedShipments = new ArrayList<>();
	private final AtomicLong shipmentIds = new AtomicLong(900);

	@BeforeEach
	void setUp() {
		service = new LegacyShipmentBackfillService(lineItemRepository, orderRepository, shipmentRepository);

		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
			Shipment s = inv.getArgument(0);
			if (s.getId() == null) {
				ReflectionTestUtils.setField(s, "id", shipmentIds.incrementAndGet());
			}
			if (!savedShipments.contains(s)) {
				savedShipments.add(s);
			}
			return s;
		});
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(anyLong(), anyString()))
			.thenReturn(Optional.empty());
		when(lineItemRepository.save(any(OrderLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	private Order order(Long id, MarketType market, String marketOrderNo) {
		Order order = Order.builder()
			.marketType(market).marketOrderNo(marketOrderNo).build();
		ReflectionTestUtils.setField(order, "id", id);
		when(orderRepository.findById(id)).thenReturn(Optional.of(order));
		return order;
	}

	private OrderLineItem unlinkedItem(Long id, Long orderId, String trackingNo,
		ShippingCarrier carrier, Boolean sentToMarket) {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(orderId).quantity(1)
			.shippingData(ShippingData.builder()
				.trackingNo(trackingNo).shippingCarrier(carrier)
				.trackingSentToMarket(sentToMarket).shippingStatus(ShippingStatus.SHIPPED)
				.build())
			.build();
		ReflectionTestUtils.setField(item, "id", id);
		return item;
	}

	@Test
	@DisplayName("쿠팡은 배송 식별자를 지어내지 않고 건너뛴다 — 주문번호를 박스번호 자리에 넣으면 마켓이 거부한다")
	void skipsCoupangRatherThanFabricatingBoxId() {
		// 쿠팡의 배송 식별자(배송박스번호)는 송장 등록·수정 API가 요구하는 실제 값이다.
		// 주문번호로 대체하면 마켓 거부가 마켓의 상태 잠금처럼 보인다(D-127에서 겪은 것).
		// 기존 109건은 2026-08-07 백필 때 sb_order.shipment_box_id에서 정확한 값을 받아 이미 연결됐다.
		order(10L, MarketType.COUPANG, "700000012345");
		OrderLineItem item = unlinkedItem(500L, 10L, "123456789012", ShippingCarrier.CJ_LOGISTICS, true);
		when(lineItemRepository.findByShipmentIdIsNull()).thenReturn(List.of(item));

		Map<String, Object> result = service.backfill();

		assertThat(savedShipments).isEmpty();
		assertThat(item.getShipmentId()).isNull();
		assertThat(result).containsEntry("skipped", 1).containsEntry("linked", 0);
	}

	@Test
	@DisplayName("라인아이템의 송장·택배사·마켓보유플래그를 배송으로 승격한다")
	void promotesLineItemTrackingToShipment() {
		order(15L, MarketType.ELEVEN_STREET, "20260731088778990");
		OrderLineItem item = unlinkedItem(506L, 15L, "123456789012", ShippingCarrier.CJ_LOGISTICS, true);
		when(lineItemRepository.findByShipmentIdIsNull()).thenReturn(List.of(item));

		Map<String, Object> result = service.backfill();

		Shipment shipment = savedShipments.get(0);
		assertThat(shipment.getTrackingNo()).isEqualTo("123456789012");
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipment.getTrackingSentToMarket()).isTrue();
		assertThat(item.getShipmentId()).isEqualTo(shipment.getId());
		assertThat(result).containsEntry("linked", 1).containsEntry("created", 1);
	}

	@Test
	@DisplayName("배송 식별자를 모르면 주문번호로 대체한다(설계 §3.3) — 배송 없는 라인아이템을 남기지 않는다")
	void fallsBackToMarketOrderNo() {
		order(11L, MarketType.ELEVEN_STREET, "20260731088778989");
		OrderLineItem item = unlinkedItem(501L, 11L, null, null, null);
		when(lineItemRepository.findByShipmentIdIsNull()).thenReturn(List.of(item));

		service.backfill();

		assertThat(savedShipments).hasSize(1);
		assertThat(savedShipments.get(0).getMarketShipmentNo()).isEqualTo("20260731088778989");
		assertThat(item.getShipmentId()).isNotNull();
	}

	@Test
	@DisplayName("송장이 없는 레거시 행도 배송을 갖는다 — 값이 없을 뿐 배송은 존재한다")
	void createsShipmentEvenWithoutTracking() {
		order(12L, MarketType.SMART_STORE, "2026052188084271");
		OrderLineItem item = unlinkedItem(502L, 12L, null, null, null);
		when(lineItemRepository.findByShipmentIdIsNull()).thenReturn(List.of(item));

		service.backfill();

		assertThat(savedShipments.get(0).getTrackingNo()).isNull();
		assertThat(item.getShipmentId()).isNotNull();
	}

	@Test
	@DisplayName("같은 주문에 이미 그 식별자의 배송이 있으면 재사용한다 — 두 번 돌려도 늘지 않는다(멱등)")
	void reusesExistingShipment() {
		order(13L, MarketType.GMARKET, "20260730-0000016");
		Shipment existing = Shipment.builder().orderId(13L).marketShipmentNo("20260730-0000016").build();
		ReflectionTestUtils.setField(existing, "id", 777L);
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(13L, "20260730-0000016"))
			.thenReturn(Optional.of(existing));
		OrderLineItem item = unlinkedItem(503L, 13L, "999999999999", ShippingCarrier.HANJIN, false);
		when(lineItemRepository.findByShipmentIdIsNull()).thenReturn(List.of(item));

		Map<String, Object> result = service.backfill();

		assertThat(item.getShipmentId()).isEqualTo(777L);
		assertThat(result).containsEntry("created", 0).containsEntry("linked", 1);
	}

	@Test
	@DisplayName("주문이 없는 고아 라인아이템은 건너뛰고 나머지를 계속 처리한다")
	void skipsOrphanLineItems() {
		when(orderRepository.findById(99L)).thenReturn(Optional.empty());
		OrderLineItem orphan = unlinkedItem(504L, 99L, null, null, null);
		order(14L, MarketType.GMARKET, "20260805-0000011");
		OrderLineItem good = unlinkedItem(505L, 14L, null, null, null);
		when(lineItemRepository.findByShipmentIdIsNull()).thenReturn(List.of(orphan, good));

		Map<String, Object> result = service.backfill();

		assertThat(result).containsEntry("skipped", 1).containsEntry("linked", 1);
		assertThat(orphan.getShipmentId()).isNull();
		assertThat(good.getShipmentId()).isNotNull();
	}
}

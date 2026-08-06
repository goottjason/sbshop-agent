package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

/**
 * 3계층 반영의 <b>마켓 공통 규율</b>을 한 곳에서 고정한다.
 *
 * <p>11번가(D-134)·쿠팡(D-137)을 전환하며 이 로직이 거의 그대로 두 번 복제됐고, 규율도 두 곳에서
 * 따로 검증되고 있었다. 골격을 추출한 뒤로는 <b>여기가 정본</b>이다 — Cafe24·N스토어를 더할 때
 * 같은 규율을 다시 테스트하지 않아도 되고, 규율을 바꿀 때 고칠 곳이 한 군데다.
 *
 * <p>마켓별 테스트는 <b>정책만</b> 검증하면 된다(상품 해석·정산액 산출).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketLineItemSyncDispatcherTest {

	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ShipmentRepository shipmentRepository;

	private MarketLineItemSyncDispatcher dispatcher;

	private final List<OrderLineItem> saved = new ArrayList<>();
	private final AtomicLong lineItemIds = new AtomicLong(1000);
	private final AtomicLong shipmentIds = new AtomicLong(10);
	/** 정책이 해석해 줄 상품 ID — key는 상품주문 식별자. 없으면 매핑 불가를 뜻한다. */
	private final Map<String, Long> productBySeq = new LinkedHashMap<>();
	private int createCalls;

	@BeforeEach
	void setUp() {
		dispatcher = new MarketLineItemSyncDispatcher(orderLineItemRepository,
			new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository));

		when(orderLineItemRepository.save(any(OrderLineItem.class))).thenAnswer(inv -> {
			OrderLineItem li = inv.getArgument(0);
			if (li.getId() == null) {
				ReflectionTestUtils.setField(li, "id", lineItemIds.incrementAndGet());
			}
			if (!saved.contains(li)) {
				saved.add(li);
			}
			return li;
		});
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
			Shipment sh = inv.getArgument(0);
			if (sh.getId() == null) {
				ReflectionTestUtils.setField(sh, "id", shipmentIds.incrementAndGet());
			}
			return sh;
		});
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(any(), anyString()))
			.thenReturn(Optional.empty());
		when(orderLineItemRepository.findByShipmentId(any())).thenReturn(List.of());
	}

	/** 마켓 정책 대역 — 상품 해석과 생성만 담당한다(골격이 갖지 않는 것). */
	private final MarketLineItemSyncPolicy policy = new MarketLineItemSyncPolicy() {
		@Override
		public String logTag() {
			return "TEST_MARKET";
		}

		@Override
		public Long resolveProductId(MarketLineItemDto dto) {
			return productBySeq.get(dto.getMarketLineItemNo());
		}

		@Override
		public OrderLineItem createLineItem(MarketLineItemDto dto, Long orderId, Long productId) {
			createCalls++;
			return OrderLineItem.builder()
				.orderId(orderId).productId(productId)
				.quantity(dto.getQuantity() != null ? dto.getQuantity() : 0)
				.marketLineItemNo(dto.getMarketLineItemNo())
				.shippingData(ShippingData.builder().shippingStatus(dto.getStatus()).build())
				.build();
		}
	};

	private Order order() {
		Order o = Order.builder().marketOrderNo("ORD-1").build();
		ReflectionTestUtils.setField(o, "id", 55L);
		return o;
	}

	private MarketLineItemDto item(String seq, ShippingStatus status) {
		return MarketLineItemDto.builder()
			.marketLineItemNo(seq).quantity(1)
			.orderPrice(BigDecimal.TEN).totalAmount(BigDecimal.TEN)
			.status(status).build();
	}

	private MarketShipmentDto shipment(String no, String tracking, MarketLineItemDto... items) {
		return MarketShipmentDto.builder()
			.marketShipmentNo(no).trackingNo(tracking)
			.carrier(tracking == null ? null : ShippingCarrier.CJ_LOGISTICS)
			.lineItems(List.of(items)).build();
	}

	private MarketOrderDto dto(MarketShipmentDto... shipments) {
		return MarketOrderDto.builder()
			.marketOrderNo("ORD-1").shipments(List.of(shipments)).build();
	}

	private OrderLineItem legacy(Long productId) {
		OrderLineItem li = OrderLineItem.builder().orderId(55L).quantity(1).productId(productId).build();
		ReflectionTestUtils.setField(li, "id", 500L);
		return li;
	}

	@Test
	@DisplayName("배송 계층이 없으면 아무것도 하지 않는다 — 평면 DTO가 새어들어도 파괴하지 않는다")
	void skipsWhenNoShipments() {
		dispatcher.sync(order(), MarketOrderDto.builder().marketOrderNo("ORD-1").build(),
			List.of(), policy);

		assertThat(saved).isEmpty();
		assertThat(createCalls).isZero();
	}

	@Test
	@DisplayName("상품 해석은 상품주문당 한 번만 부른다 — 부수효과 있는 구현이 중복 실행되면 안 된다")
	void resolvesProductOncePerLineItem() {
		// 쿠팡 구현은 vendorItemId 보강 저장을 한다. 두 번 부르면 저장이 두 번 일어났다(2026-08-06).
		List<String> calls = new ArrayList<>();
		MarketLineItemSyncPolicy counting = new MarketLineItemSyncPolicy() {
			@Override
			public String logTag() {
				return "TEST_MARKET";
			}

			@Override
			public Long resolveProductId(MarketLineItemDto dto) {
				calls.add(dto.getMarketLineItemNo());
				return 7L;
			}

			@Override
			public OrderLineItem createLineItem(MarketLineItemDto d, Long orderId, Long productId) {
				return policy.createLineItem(d, orderId, productId);
			}
		};

		dispatcher.sync(order(), dto(shipment("D1", "T1",
			item("1", ShippingStatus.NEW), item("2", ShippingStatus.NEW))), List.of(), counting);

		assertThat(calls).containsExactly("1", "2");
	}

	@Test
	@DisplayName("매칭은 주문 전체에서 한 번 한다 — 상품주문이 다른 배송으로 옮겨가도 중복이 생기지 않는다")
	void matchesAcrossShipmentBoundary() {
		// 배송별로 나눠 매칭하면 같은 기존 행을 두 배송이 각각 채택해 라인아이템이 두 벌 생긴다.
		OrderLineItem existing = legacy(7L);
		existing.assignMarketLineItemNo("2");
		productBySeq.put("1", 8L);
		productBySeq.put("2", 7L);

		dispatcher.sync(order(), dto(
			shipment("D1", "T1", item("1", ShippingStatus.NEW)),
			shipment("D2", "T2", item("2", ShippingStatus.SHIPPED))),
			new ArrayList<>(List.of(existing)), policy);

		// 기존 행은 두 번째 배송의 상품주문으로 정확히 짝지어지고, 첫 번째만 새로 생긴다.
		assertThat(createCalls).isEqualTo(1);
		assertThat(existing.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(existing.getShipmentId()).isNotNull();
	}

	@Test
	@DisplayName("송장은 배송에서 미러로 내려온다 — 골격이 직접 쓰지 않는다")
	void mirrorsTrackingFromShipment() {
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("D1", "424079080471", item("1", ShippingStatus.SHIPPED))),
			List.of(), policy);

		OrderLineItem created = saved.get(0);
		assertThat(created.getShippingData().getTrackingNo()).isEqualTo("424079080471");
		assertThat(created.getShippingData().getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(created.getShippingData().getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("UNKNOWN 상태는 기존 상태를 덮지 않는다")
	void unknownStatusDoesNotOverwrite() {
		OrderLineItem existing = legacy(7L);
		existing.assignMarketLineItemNo("1");
		existing.applyShippingData(ShippingData.builder().shippingStatus(ShippingStatus.DELIVERED).build());
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("D1", null, item("1", ShippingStatus.UNKNOWN))),
			new ArrayList<>(List.of(existing)), policy);

		assertThat(existing.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("상품을 식별할 수 없고 짝짓지 못한 기존 행이 있으면 분할을 미룬다")
	void defersSplitWhenProductUnidentifiable() {
		// 빈 껍데기 행과 고아 행을 동시에 만드는 조합. 라이브에서 실제로 터졌다(D-136).
		OrderLineItem existing = OrderLineItem.builder()
			.orderId(55L).quantity(1).productId(7L)
			.purchaseStatus(PurchaseStatus.PURCHASED)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHB-1").build())
			.build();
		ReflectionTestUtils.setField(existing, "id", 500L);
		// productBySeq 비움 → 두 상품주문 모두 매핑 불가

		dispatcher.sync(order(), dto(shipment("D1", "T1",
			item("1", ShippingStatus.SHIPPED), item("2", ShippingStatus.SHIPPED))),
			new ArrayList<>(List.of(existing)), policy);

		assertThat(createCalls).isZero();
		assertThat(saved).isEmpty();
		assertThat(existing.getSourcingData().getSourcingOrderNo()).isEqualTo("IHB-1");
	}

	@Test
	@DisplayName("상품을 식별할 수 있으면 가드가 정상 분할을 막지 않는다")
	void guardDoesNotBlockIdentifiableSplit() {
		OrderLineItem existing = legacy(7L);
		productBySeq.put("1", 7L);
		productBySeq.put("2", 8L);

		dispatcher.sync(order(), dto(shipment("D1", "T1",
			item("1", ShippingStatus.SHIPPED), item("2", ShippingStatus.SHIPPED))),
			new ArrayList<>(List.of(existing)), policy);

		assertThat(existing.getMarketLineItemNo()).isEqualTo("1");
		assertThat(createCalls).isEqualTo(1);
	}

	@Test
	@DisplayName("마켓이 더는 보내지 않는 기존 행은 지우지 않는다")
	void neverDeletesUnclaimedRows() {
		OrderLineItem stale = legacy(7L);
		stale.assignMarketLineItemNo("9");
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("D1", "T1", item("1", ShippingStatus.SHIPPED))),
			new ArrayList<>(List.of(stale)), policy);

		// 우리 고유 정보가 붙어 있을 수 있으므로 남긴다. 삭제 호출이 없어야 한다.
		assertThat(stale.getMarketLineItemNo()).isEqualTo("9");
		assertThat(saved).doesNotContain(stale);
	}

	@Test
	@DisplayName("같은 배송을 여러 상품주문이 가리키면 배송은 한 번만 upsert된다")
	void upsertsEachShipmentOnce() {
		productBySeq.put("1", 7L);
		productBySeq.put("2", 8L);

		dispatcher.sync(order(), dto(shipment("D1", "T1",
			item("1", ShippingStatus.NEW), item("2", ShippingStatus.NEW))), List.of(), policy);

		// 배송 저장은 배송식별자당 1회 — 상품주문 수만큼 부르면 중복 생성·경합이 생긴다.
		org.mockito.Mockito.verify(shipmentRepository, org.mockito.Mockito.times(1))
			.save(any(Shipment.class));
		assertThat(saved).hasSize(2);
	}
}

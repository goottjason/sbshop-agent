package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.order.vo.SettlementData;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketLineItemSyncDispatcherTest {
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	private MarketLineItemSyncDispatcher dispatcher;

	private final List<OrderLineItem> saved = new ArrayList<>();
	private final AtomicLong lineItemIds = new AtomicLong(1000);
	private final AtomicLong shipmentIds = new AtomicLong(10);
	private final Map<String, Long> productBySeq = new LinkedHashMap<>();
	private int createCalls;
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void setUp() {
		logs.start();
		((Logger)LoggerFactory.getLogger(MarketLineItemSyncDispatcher.class)).addAppender(logs);
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

	@AfterEach
	void tearDown() {
		((Logger)LoggerFactory.getLogger(MarketLineItemSyncDispatcher.class))
			.detachAppender(logs);
	}

	@Test
	@DisplayName("배송 계층이 없으면 아무것도 하지 않는다 — 평면 DTO가 새어들어도 파괴하지 않는다")
	void skipsWhenNoShipments() {
		dispatcher.sync(order(), MarketOrderDto.builder().marketOrderNo("ORD-1").build(),
			List.of(), policy);

		assertThat(saved).isEmpty();
		assertThat(createCalls).isZero();
	}

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

		@Override
		public BigDecimal settlementAmount(MarketLineItemDto dto) {
			return dto.getSettlementAmount();
		}
	};

	@Test
	@DisplayName("상품 해석은 상품주문당 한 번만 부른다 — 부수효과 있는 구현이 중복 실행되면 안 된다")
	void resolvesProductOncePerLineItem() {
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
			public BigDecimal settlementAmount(MarketLineItemDto d) {
				return d.getSettlementAmount();
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
		OrderLineItem existing = legacy(7L);
		existing.assignMarketLineItemNo("2");
		productBySeq.put("1", 8L);
		productBySeq.put("2", 7L);

		dispatcher.sync(order(), dto(
			shipment("D1", "T1", item("1", ShippingStatus.NEW)),
			shipment("D2", "T2", item("2", ShippingStatus.SHIPPED))),
			new ArrayList<>(List.of(existing)), policy);

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
		OrderLineItem existing = OrderLineItem.builder()
			.orderId(55L).quantity(1).productId(7L)
			.purchaseStatus(PurchaseStatus.PURCHASED)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHB-1").build())
			.build();
		ReflectionTestUtils.setField(existing, "id", 500L);

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

		Mockito.verify(shipmentRepository, Mockito.times(1))
			.save(any(Shipment.class));
		assertThat(saved).hasSize(2);
	}

	@Test
	@DisplayName("[D-160] 종결 전인데 정산액이 0이면 마켓 값으로 되살린다 — 거짓 취소가 남긴 손상을 스스로 복구한다")
	void recoversZeroedSettlementOnLiveLineItem() {
		OrderLineItem damaged = existing("1", ShippingStatus.CANCELED, "0", true);
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("B1", null,
			itemWithSettlement("1", ShippingStatus.SHIPPED, "25098"))),
			new ArrayList<>(List.of(damaged)), policy);

		assertThat(damaged.getSettlementData().getSettlementAmount()).isEqualByComparingTo("25098");
		assertThat(damaged.getSettlementData().getSettlementVerified()).isFalse();
	}

	@Test
	@DisplayName("[D-160] 정상 정산액은 건드리지 않는다 — 이미 대조한 과거 수치를 동기화가 흔들면 안 된다")
	void leavesHealthySettlementAlone() {
		OrderLineItem healthy = existing("1", ShippingStatus.SHIPPED, "47314", true);
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("B1", null,
			itemWithSettlement("1", ShippingStatus.SHIPPED, "49887"))),
			new ArrayList<>(List.of(healthy)), policy);

		assertThat(healthy.getSettlementData().getSettlementAmount()).isEqualByComparingTo("47314");
		assertThat(healthy.getSettlementData().getSettlementVerified()).isTrue();
	}

	@Test
	@DisplayName("[D-160] 환불성 종결의 정산0은 되살리지 않는다 — 그 0은 사실이다 (D-098)")
	void doesNotResurrectSettlementForRefundedLineItem() {
		OrderLineItem refunded = existing("1", ShippingStatus.RETURNED, "0", true);
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("B1", null,
			itemWithSettlement("1", ShippingStatus.RETURNED, "25098"))),
			new ArrayList<>(List.of(refunded)), policy);

		assertThat(refunded.getSettlementData().getSettlementAmount()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("[D-160] 마켓이 금액을 주지 않으면 0을 유지한다 — 근거 없는 값을 지어내지 않는다")
	void keepsZeroWhenMarketGivesNoAmount() {
		OrderLineItem damaged = existing("1", ShippingStatus.SHIPPED, "0", true);
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("B1", null,
			itemWithSettlement("1", ShippingStatus.SHIPPED, null))),
			new ArrayList<>(List.of(damaged)), policy);

		assertThat(damaged.getSettlementData().getSettlementAmount()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("상품을 못 붙인 라인아이템은 경고로 드러낸다 — 마켓 식별자를 실어서")
	void warnsWhenLineItemEndsWithoutProduct() {
		MarketLineItemDto orphan = MarketLineItemDto.builder()
			.marketLineItemNo("1").quantity(2)
			.orderPrice(BigDecimal.TEN).totalAmount(BigDecimal.TEN)
			.productName("Pure Indian Foods 오리지널 기버터")
			.status(ShippingStatus.PREPARING)
			.marketSpecificData(Map.of("product_no", "-99999", "product_code", "2005125893"))
			.build();

		dispatcher.sync(order(), dto(shipment("D1", null, orphan)), new ArrayList<>(), policy);

		assertThat(warnings()).anySatisfy(msg -> assertThat(msg)
			.contains("상품 미매핑")
			.contains("ORD-1")
			.contains("1")
			.contains("Pure Indian Foods 오리지널 기버터")
			.contains("2005125893"));
	}

	@Test
	@DisplayName("상품이 붙으면 미매핑 경고를 내지 않는다")
	void doesNotWarnWhenProductResolved() {
		productBySeq.put("1", 7L);

		dispatcher.sync(order(), dto(shipment("D1", "T1", item("1", ShippingStatus.SHIPPED))),
			new ArrayList<>(), policy);

		assertThat(warnings()).noneMatch(msg -> msg.contains("상품 미매핑"));
	}

	@Test
	@DisplayName("정책이 상품을 못 줘도 채택한 기존 행이 이미 알고 있으면 경고하지 않는다")
	void doesNotWarnWhenAdoptedRowAlreadyKnowsProduct() {
		OrderLineItem existing = legacy(7L);

		dispatcher.sync(order(), dto(shipment("D1", "T1", item("1", ShippingStatus.SHIPPED))),
			new ArrayList<>(List.of(existing)), policy);

		assertThat(existing.getProductId()).isEqualTo(7L);
		assertThat(warnings()).noneMatch(msg -> msg.contains("상품 미매핑"));
	}

	private List<String> warnings() {
		return logs.list.stream()
			.filter(e -> e.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

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

	private MarketLineItemDto itemWithSettlement(String seq, ShippingStatus status, String settlement) {
		return MarketLineItemDto.builder()
			.marketLineItemNo(seq).quantity(1)
			.orderPrice(BigDecimal.TEN).totalAmount(BigDecimal.TEN)
			.settlementAmount(settlement == null ? null : new BigDecimal(settlement))
			.status(status).build();
	}

	private OrderLineItem existing(String seq, ShippingStatus status, String settlement,
		boolean verified) {
		OrderLineItem li = OrderLineItem.builder().orderId(55L).quantity(1).productId(7L)
			.marketLineItemNo(seq)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.settlementData(SettlementData.builder()
				.settlementAmount(settlement == null ? null : new BigDecimal(settlement))
				.settlementVerified(verified).build())
			.build();
		ReflectionTestUtils.setField(li, "id", 467L);
		return li;
	}
}

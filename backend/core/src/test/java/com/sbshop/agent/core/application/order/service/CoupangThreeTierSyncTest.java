package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import com.sbshop.agent.core.domain.product.ProductRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangThreeTierSyncTest {
	private static final String ORDER_NO = "2101945764711";
	private static final String BOX = "714841543016459";

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private CoupangOrderAdapter adapter;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private TerminalSettlementService terminalSettlementService;
	@Mock
	private ActionLogService actionLogService;
	@Mock
	private ShipmentRepository shipmentRepository;

	private CoupangOrderSyncService service;

	private final List<OrderLineItem> savedLineItems = new ArrayList<>();
	private final AtomicLong lineItemIds = new AtomicLong(700);
	private final AtomicLong shipmentIds = new AtomicLong(80);

	@BeforeEach
	void setUp() {
		MarketLineItemSyncDispatcher syncDispatcher = new MarketLineItemSyncDispatcher(orderLineItemRepository,
			new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository), org.mockito.Mockito.mock(TrackingMismatchResolver.class));
		service = new CoupangOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, marketRegistrationRepository,
			new MarketRegistrationLookup(marketRegistrationRepository), eventPublisher,
			adapter, new CoupangStatusMapper(), syncStatusService, marketFeeService,
			terminalSettlementService, actionLogService, syncDispatcher);

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("A001");
		when(credential.getAccessKey()).thenReturn("ak");
		when(credential.getSecretKey()).thenReturn("sk");
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(credential));

		when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
			Order o = inv.getArgument(0);
			if (o.getId() == null) {
				ReflectionTestUtils.setField(o, "id", 300L);
			}
			return o;
		});
		when(orderLineItemRepository.save(any(OrderLineItem.class))).thenAnswer(inv -> {
			OrderLineItem li = inv.getArgument(0);
			if (li.getId() == null) {
				ReflectionTestUtils.setField(li, "id", lineItemIds.incrementAndGet());
			}
			if (!savedLineItems.contains(li)) {
				savedLineItems.add(li);
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
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(anyLong(), anyString()))
			.thenReturn(Optional.empty());
		when(orderLineItemRepository.findByShipmentId(anyLong())).thenReturn(List.of());
		when(marketFeeService.settlementAmount(any(), any()))
			.thenAnswer(inv -> inv.getArgument(0) == null ? BigDecimal.ZERO
				: ((BigDecimal)inv.getArgument(0)).multiply(new BigDecimal("0.89")));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of());
	}

	@Test
	@DisplayName("단일 상품 기존 주문은 라인아이템이 늘지 않는다 — 현재 192행 회귀")
	void doesNotDuplicateForSingleItemOrders() {
		Order existing = Order.builder().marketType(MarketType.COUPANG).marketOrderNo(ORDER_NO).build();
		ReflectionTestUtils.setField(existing, "id", 300L);
		OrderLineItem legacy = OrderLineItem.builder().orderId(300L).quantity(1).productId(555L).build();
		ReflectionTestUtils.setField(legacy, "id", 900L);

		when(orderRepository.findByMarketOrderNo(ORDER_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(300L)).thenReturn(new ArrayList<>(List.of(legacy)));
		stubRegistration("87763005527", 555L);

		runSync(orderDto(shipment(BOX, "315399491013",
			item(BOX + ":87763005527", "87763005527", "스테비아", 1, "24600", ShippingStatus.SHIPPED))));

		assertThat(savedLineItems).containsExactly(legacy);
		assertThat(legacy.getMarketLineItemNo()).isEqualTo(BOX + ":87763005527");
		assertThat(legacy.getShipmentId()).isNotNull();
		assertThat(legacy.getShippingData().getTrackingNo()).isEqualTo("315399491013");
	}

	@Test
	@DisplayName("신규 다품목 주문은 상품마다 라인아이템을 만든다 — 종전엔 orderItems[0]만 저장했다")
	void createsOneLineItemPerOrderItem() {
		when(orderRepository.findByMarketOrderNo(ORDER_NO)).thenReturn(Optional.empty());
		stubRegistration("111", 501L);
		stubRegistration("222", 502L);

		runSync(orderDto(shipment(BOX, "315399491013",
			item(BOX + ":111", "111", "칼슘", 1, "10000", ShippingStatus.SHIPPED),
			item(BOX + ":222", "222", "마그네슘", 2, "7000", ShippingStatus.SHIPPED))));

		assertThat(savedLineItems).hasSize(2);
		assertThat(saved(BOX + ":111").getProductId()).isEqualTo(501L);
		assertThat(saved(BOX + ":222").getProductId()).isEqualTo(502L);
		assertThat(saved(BOX + ":222").getSettlementData().getSettlementAmount())
			.isEqualByComparingTo("12460.00");
		assertThat(saved(BOX + ":111").getShipmentId())
			.isEqualTo(saved(BOX + ":222").getShipmentId());
	}

	@Test
	@DisplayName("분할배송은 배송 2건으로 나뉘고 송장이 서로 섞이지 않는다")
	void splitShippingCreatesTwoShipments() {
		when(orderRepository.findByMarketOrderNo(ORDER_NO)).thenReturn(Optional.empty());
		stubRegistration("111", 501L);
		stubRegistration("222", 502L);

		runSync(orderDto(
			shipment("77001122", "111111111111",
				item("77001122:111", "111", "칼슘", 1, "10000", ShippingStatus.SHIPPED)),
			shipment("77001133", "222222222222",
				item("77001133:222", "222", "마그네슘", 1, "7000", ShippingStatus.NEW))));

		assertThat(savedLineItems).hasSize(2);
		assertThat(saved("77001122:111").getShippingData().getTrackingNo()).isEqualTo("111111111111");
		assertThat(saved("77001133:222").getShippingData().getTrackingNo()).isEqualTo("222222222222");
		assertThat(saved("77001122:111").getShipmentId())
			.isNotEqualTo(saved("77001133:222").getShipmentId());
		assertThat(saved("77001133:222").getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("취소된 상품만 CANCELED로 반영된다 — 형제 상품은 그대로다")
	void appliesPerItemCancellation() {
		when(orderRepository.findByMarketOrderNo(ORDER_NO)).thenReturn(Optional.empty());
		stubRegistration("111", 501L);
		stubRegistration("222", 502L);

		runSync(orderDto(shipment(BOX, "315399491013",
			item(BOX + ":111", "111", "칼슘", 1, "10000", ShippingStatus.SHIPPED),
			item(BOX + ":222", "222", "마그네슘", 1, "7000", ShippingStatus.CANCELED))));

		assertThat(saved(BOX + ":111").getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.SHIPPED);
		assertThat(saved(BOX + ":222").getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("UNKNOWN 상태는 기존 상태를 덮지 않는다")
	void unknownStatusDoesNotOverwrite() {
		Order existing = Order.builder().marketType(MarketType.COUPANG).marketOrderNo(ORDER_NO).build();
		ReflectionTestUtils.setField(existing, "id", 300L);
		OrderLineItem legacy = OrderLineItem.builder()
			.orderId(300L).quantity(1).productId(555L).marketLineItemNo(BOX + ":111")
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.DELIVERED).build())
			.build();
		ReflectionTestUtils.setField(legacy, "id", 900L);

		when(orderRepository.findByMarketOrderNo(ORDER_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(300L)).thenReturn(new ArrayList<>(List.of(legacy)));
		stubRegistration("111", 555L);

		runSync(orderDto(shipment(BOX, "315399491013",
			item(BOX + ":111", "111", "칼슘", 1, "10000", ShippingStatus.UNKNOWN))));

		assertThat(legacy.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("상품을 식별할 수 없으면 분할을 미룬다 — 빈 껍데기 행과 고아 행을 만들지 않는다")
	void defersSplitWhenProductUnidentifiable() {
		Order existing = Order.builder().marketType(MarketType.COUPANG).marketOrderNo(ORDER_NO).build();
		ReflectionTestUtils.setField(existing, "id", 300L);
		OrderLineItem legacy = OrderLineItem.builder()
			.orderId(300L).quantity(1).productId(555L)
			.purchaseStatus(PurchaseStatus.PURCHASED)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHB-999").build())
			.build();
		ReflectionTestUtils.setField(legacy, "id", 900L);

		when(orderRepository.findByMarketOrderNo(ORDER_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(300L)).thenReturn(new ArrayList<>(List.of(legacy)));

		runSync(orderDto(shipment(BOX, "315399491013",
			item(BOX + ":111", "111", "칼슘", 1, "10000", ShippingStatus.SHIPPED),
			item(BOX + ":222", "222", "마그네슘", 1, "7000", ShippingStatus.SHIPPED))));

		assertThat(savedLineItems).isEmpty();
		assertThat(legacy.getSourcingData().getSourcingOrderNo()).isEqualTo("IHB-999");
		assertThat(legacy.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
	}

	private void stubRegistration(String vendorItemId, Long sbProductId) {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(sbProductId)
			.sbProductId(sbProductId)
			.marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"vendorItemId\":\"" + vendorItemId + "\"}")
			.build();
		when(marketRegistrationRepository.findIdentifierCandidates(
			MarketType.COUPANG, vendorItemId)).thenReturn(List.of(reg));
	}

	private MarketLineItemDto item(String key, String vendorItemId, String name,
		int qty, String price, ShippingStatus status) {
		BigDecimal unit = new BigDecimal(price);
		return MarketLineItemDto.builder()
			.marketLineItemNo(key)
			.marketProductCode(vendorItemId)
			.productName(name)
			.quantity(qty)
			.orderPrice(unit)
			.totalAmount(unit.multiply(BigDecimal.valueOf(qty)))
			.status(status)
			.build();
	}

	private MarketOrderDto orderDto(MarketShipmentDto... shipments) {
		return MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo(ORDER_NO)
			.recipientName("홍길동")
			.zipcode("07997")
			.address("서울 양천구 101동")
			.orderDate(LocalDateTime.of(2026, 7, 30, 2, 10))
			.shipments(List.of(shipments))
			.build();
	}

	private MarketShipmentDto shipment(String boxId, String tracking, MarketLineItemDto... items) {
		return MarketShipmentDto.builder()
			.marketShipmentNo(boxId)
			.trackingNo(tracking)
			.carrier(tracking == null ? null : ShippingCarrier.LOTTE_LOGISTICS)
			.lineItems(List.of(items))
			.build();
	}

	private void runSync(MarketOrderDto dto) {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any())).thenReturn(
			new MarketFetchOutcome(List.of(dto), true));
		service.syncCoupangOrders();
	}

	private OrderLineItem saved(String key) {
		return savedLineItems.stream().filter(li -> key.equals(li.getMarketLineItemNo()))
			.findFirst().orElseThrow(() -> new AssertionError("상품주문 " + key + " 저장 안 됨"));
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.SmartStoreOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
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
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;

/**
 * 5단계: N스토어 동기화가 3계층 DTO를 소비한다.
 *
 * <p>다른 마켓과 성격이 하나 다르다 — <b>주문 키가 바뀐다.</b> 종전에는 상품주문번호
 * ({@code productOrderId})를 주문번호로 썼고, 이제 {@code orderId}를 쓴다. 기존 22건이 옛 키로
 * 저장돼 있으므로, 새 키로 못 찾으면 <b>옛 키로 찾아 키를 갈아 끼운다</b>. 그러지 않으면 같은
 * 주문이 두 행이 되고 옛 행에 붙은 소싱·구매정보가 고아가 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreThreeTierSyncTest {

	private static final String ORDER_ID = "2026072134143761";
	private static final String PO_1 = "2026072112554021";
	private static final String PO_2 = "2026072112554022";
	private static final String PKG = "2026072160001571";

	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ShipmentRepository shipmentRepository;
	@Mock private ProductRepository productRepository;
	@Mock private ApplicationEventPublisher eventPublisher;
	@Mock private SmartStoreOrderAdapter adapter;
	@Mock private SyncStatusService syncStatusService;
	@Mock private MarketFeeService marketFeeService;
	@Mock private TerminalSettlementService terminalSettlementService;

	private SmartStoreOrderSyncService service;

	private final List<OrderLineItem> savedLineItems = new ArrayList<>();
	private final List<Order> savedOrders = new ArrayList<>();
	private final AtomicLong lineItemIds = new AtomicLong(700);
	private final AtomicLong shipmentIds = new AtomicLong(900);

	@BeforeEach
	void setUp() {
		// 골격은 진짜 객체를 끼운다 — 목이면 반영이 사라져 검증이 통과해도 아무것도 증명하지 못한다(D-133).
		MarketLineItemSyncDispatcher syncDispatcher = new MarketLineItemSyncDispatcher(orderLineItemRepository,
			new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository));
		service = new SmartStoreOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, eventPublisher, adapter,
			syncStatusService, marketFeeService, terminalSettlementService, syncDispatcher);

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("clientId");
		when(credential.getSecretKey()).thenReturn("secret");
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE))
			.thenReturn(Optional.of(credential));

		when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
			Order o = inv.getArgument(0);
			if (o.getId() == null) {
				ReflectionTestUtils.setField(o, "id", 42L);
			}
			if (!savedOrders.contains(o)) {
				savedOrders.add(o);
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
			Shipment s = inv.getArgument(0);
			if (s.getId() == null) {
				ReflectionTestUtils.setField(s, "id", shipmentIds.incrementAndGet());
			}
			return s;
		});
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(anyLong(), anyString()))
			.thenReturn(Optional.empty());
		when(orderLineItemRepository.findByShipmentId(anyLong())).thenReturn(List.of());
		// 요율 추정(실측값이 없을 때만 쓰인다) — 4.9% 수수료 가정.
		when(marketFeeService.settlementAmount(any(), any()))
			.thenAnswer(inv -> inv.getArgument(0) == null ? BigDecimal.ZERO
				: ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("0.951")));
	}

	private MarketLineItemDto item(String productOrderId, String sbCode, String amount,
		String settlement, ShippingStatus status) {
		return MarketLineItemDto.builder()
			.marketLineItemNo(productOrderId)
			.marketProductCode(sbCode)
			.productName("나우푸드 " + productOrderId)
			.quantity(1)
			.orderPrice(new BigDecimal(amount))
			.totalAmount(new BigDecimal(amount))
			.settlementAmount(settlement == null ? null : new BigDecimal(settlement))
			.status(status)
			.marketSpecificData(new java.util.HashMap<>(Map.of("productOrderId", productOrderId)))
			.build();
	}

	private MarketShipmentDto shipment(String packageNumber, String tracking, MarketLineItemDto... items) {
		return MarketShipmentDto.builder()
			.marketShipmentNo(packageNumber)
			.trackingNo(tracking)
			.carrier(tracking == null ? null : ShippingCarrier.CJ_LOGISTICS)
			.lineItems(List.of(items))
			.build();
	}

	private MarketOrderDto orderDto(MarketShipmentDto... shipments) {
		return MarketOrderDto.builder()
			.marketType(MarketType.SMART_STORE)
			.marketOrderNo(ORDER_ID)
			.recipientName("허경덕")
			.zipcode("12345")
			.address("서울시 강남구 101동 101호")
			.orderDate(LocalDateTime.of(2026, 7, 21, 10, 14))
			.marketSpecificData(new java.util.HashMap<>(
				Map.of("productOrderIds", PO_1 + "|" + PO_2)))
			.shipments(List.of(shipments))
			.build();
	}

	private void stubProduct(String sbCode, Long productId) {
		Product p = mock(Product.class);
		when(p.getId()).thenReturn(productId);
		when(productRepository.findBySbCode(sbCode)).thenReturn(Optional.of(p));
	}

	private void runSync(MarketOrderDto dto) {
		when(adapter.fetchOrders(any(), any(), any())).thenReturn(List.of(dto));
		service.syncSmartStoreOrders();
	}

	private OrderLineItem saved(String productOrderId) {
		return savedLineItems.stream()
			.filter(li -> productOrderId.equals(li.getMarketLineItemNo()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("상품주문 " + productOrderId + " 저장 안 됨"));
	}

	@Test
	@DisplayName("한 주문의 상품주문 2건이 라인아이템 2건이 된다 — 종전엔 주문이 두 행으로 쪼개졌다")
	void createsOneLineItemPerProductOrder() {
		when(orderRepository.findByMarketOrderNo(anyString())).thenReturn(Optional.empty());
		stubProduct("220522IHB016", 312L);
		stubProduct("210827OC414", 999L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.PREPARING),
			item(PO_2, "210827OC414", "40000", "38040", ShippingStatus.PREPARING))));

		assertThat(savedOrders).hasSize(1);
		assertThat(savedOrders.get(0).getMarketOrderNo()).isEqualTo(ORDER_ID);
		assertThat(savedLineItems).hasSize(2);
		assertThat(saved(PO_1).getProductId()).isEqualTo(312L);
		assertThat(saved(PO_2).getProductId()).isEqualTo(999L);
		// 같은 packageNumber = 한 배송. 둘 다 같은 배송에 연결된다.
		assertThat(saved(PO_1).getShipmentId()).isEqualTo(saved(PO_2).getShipmentId());
	}

	@Test
	@DisplayName("정산액은 마켓 실측값(expectedSettlementAmount)을 쓴다 — 요율 추정은 없을 때만")
	void usesExpectedSettlementAmount() {
		when(orderRepository.findByMarketOrderNo(anyString())).thenReturn(Optional.empty());
		stubProduct("220522IHB016", 312L);
		stubProduct("210827OC414", 999L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.PREPARING),
			item(PO_2, "210827OC414", "40000", null, ShippingStatus.PREPARING))));

		assertThat(saved(PO_1).getSettlementData().getSettlementAmount())
			.isEqualByComparingTo("62002");
		// 실측값이 없는 상품주문만 요율 추정으로 떨어진다.
		assertThat(saved(PO_2).getSettlementData().getSettlementAmount())
			.isEqualByComparingTo("38040.000");
	}

	@Test
	@DisplayName("송장은 배송에서 미러로 내려온다 — 라인아이템에 직접 쓰지 않는다")
	void trackingMirrorsFromShipment() {
		when(orderRepository.findByMarketOrderNo(anyString())).thenReturn(Optional.empty());
		stubProduct("220522IHB016", 312L);

		runSync(orderDto(shipment(PKG, "123456789012",
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.SHIPPED))));

		assertThat(saved(PO_1).getShippingData().getTrackingNo()).isEqualTo("123456789012");
		assertThat(saved(PO_1).getShippingData().getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("옛 키(productOrderId)로 저장된 주문을 찾아 orderId로 갈아 끼운다 — 중복 행을 만들지 않는다")
	void rekeysLegacyOrderFromProductOrderIdToOrderId() {
		// 이것이 5단계의 핵심 위험이다. 새 키로 못 찾는다고 신규 생성하면 같은 주문이 두 행이 되고
		// 소싱·구매정보가 붙은 옛 행이 고아가 된다.
		Order legacyOrder = Order.builder()
			.marketType(MarketType.SMART_STORE).marketOrderNo(PO_1).build();
		ReflectionTestUtils.setField(legacyOrder, "id", 77L);
		OrderLineItem legacyItem = OrderLineItem.builder()
			.orderId(77L).quantity(1).productId(312L)
			.purchaseStatus(PurchaseStatus.PURCHASED)
			.sourcingData(SourcingData.builder().sourcingOrderNo("343911144").build())
			.build();
		ReflectionTestUtils.setField(legacyItem, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORDER_ID)).thenReturn(Optional.empty());
		when(orderRepository.findByMarketOrderNo(PO_1)).thenReturn(Optional.of(legacyOrder));
		when(orderLineItemRepository.findByOrderId(77L)).thenReturn(new ArrayList<>(List.of(legacyItem)));
		stubProduct("220522IHB016", 312L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.PREPARING))));

		// 키가 갈아 끼워졌고, 새 주문은 만들어지지 않았다.
		assertThat(legacyOrder.getMarketOrderNo()).isEqualTo(ORDER_ID);
		assertThat(savedOrders).containsExactly(legacyOrder);
		// 옛 라인아이템이 채택돼 상품주문번호를 얻는다 — 소싱·구매정보는 그대로다.
		assertThat(savedLineItems).containsExactly(legacyItem);
		assertThat(legacyItem.getMarketLineItemNo()).isEqualTo(PO_1);
		assertThat(legacyItem.getSourcingData().getSourcingOrderNo()).isEqualTo("343911144");
		assertThat(legacyItem.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
	}

	@Test
	@DisplayName("이미 새 키로 저장된 주문은 옛 키를 조회하지 않는다 — 불필요한 질의 없음")
	void doesNotProbeLegacyKeyWhenNewKeyFound() {
		Order existing = Order.builder()
			.marketType(MarketType.SMART_STORE).marketOrderNo(ORDER_ID).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem item = OrderLineItem.builder()
			.orderId(42L).quantity(1).productId(312L).marketLineItemNo(PO_1).build();
		ReflectionTestUtils.setField(item, "id", 460L);

		when(orderRepository.findByMarketOrderNo(ORDER_ID)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(item)));
		stubProduct("220522IHB016", 312L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.PREPARING))));

		verify(orderRepository, never()).findByMarketOrderNo(PO_1);
		assertThat(savedLineItems).containsExactly(item);
	}

	@Test
	@DisplayName("옛 키 주문이 이미 새 키로도 존재하면 키를 갈지 않는다 — 유니크 제약 충돌 방지")
	void doesNotRekeyWhenNewKeyAlreadyExists() {
		Order migrated = Order.builder()
			.marketType(MarketType.SMART_STORE).marketOrderNo(ORDER_ID).build();
		ReflectionTestUtils.setField(migrated, "id", 42L);

		when(orderRepository.findByMarketOrderNo(ORDER_ID)).thenReturn(Optional.of(migrated));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>());
		stubProduct("220522IHB016", 312L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.PREPARING))));

		assertThat(migrated.getMarketOrderNo()).isEqualTo(ORDER_ID);
		assertThat(savedLineItems).hasSize(1);
		assertThat(saved(PO_1).getOrderId()).isEqualTo(42L);
	}

	@Test
	@DisplayName("주문 계층 마켓 데이터(productOrderIds)가 저장된다 — 발주확인·취소가 읽는다")
	void persistsOrderLevelMarketData() {
		when(orderRepository.findByMarketOrderNo(anyString())).thenReturn(Optional.empty());
		stubProduct("220522IHB016", 312L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.PREPARING))));

		assertThat(savedOrders.get(0).getMarketSpecificDataMap())
			.containsEntry("productOrderIds", PO_1 + "|" + PO_2);
	}

	@Test
	@DisplayName("진행 중 라인아이템이 있으면 주소를 마켓 값으로 덮지 않는다(D-074 회귀)")
	void doesNotOverwriteAddressWhenProgressed() {
		Order existing = Order.builder()
			.marketType(MarketType.SMART_STORE).marketOrderNo(ORDER_ID)
			.zipcode("99999").address("수기 보정 주소").build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem progressed = OrderLineItem.builder()
			.orderId(42L).quantity(1).productId(312L).marketLineItemNo(PO_1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
		ReflectionTestUtils.setField(progressed, "id", 461L);

		when(orderRepository.findByMarketOrderNo(ORDER_ID)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(progressed)));
		stubProduct("220522IHB016", 312L);

		runSync(orderDto(shipment(PKG, null,
			item(PO_1, "220522IHB016", "65200", "62002", ShippingStatus.SHIPPED))));

		assertThat(existing.getAddress()).isEqualTo("수기 보정 주소");
		assertThat(existing.getZipcode()).isEqualTo("99999");
	}
}

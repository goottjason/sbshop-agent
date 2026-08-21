package com.sbshop.agent.core.application.order.service;

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
import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
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
 * 2단계: 11번가 동기화가 3계층 DTO를 소비한다.
 *
 * <p>가장 중요한 것은 <b>정나영 건이 두 행으로 갈라지면서 우리 고유 정보를 잃지 않는 것</b>이다.
 * 기존 라인아이템 1건에는 소싱처·실구매가·구매상태가 붙어 있다. 새 매칭에 걸리지 않으면 그 행은
 * 고아가 되고, 사람이 보는 화면에는 구매정보 없는 새 행 두 개만 뜬다(D-132).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstThreeTierSyncTest {

	private static final String ORD_NO = "20260731088778989";

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ElevenstOrderAdapter adapter;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private TerminalSettlementService terminalSettlementService;
	@Mock
	private com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository marketRegistrationRepository;

	private ElevenstOrderSyncService service;

	/** 저장된 라인아이템 — 실제 리포지토리처럼 누적한다. */
	private final List<OrderLineItem> savedLineItems = new ArrayList<>();
	private final AtomicLong lineItemIds = new AtomicLong(500);
	private final AtomicLong shipmentIds = new AtomicLong(900);

	@BeforeEach
	void setUp() {
		// 골격은 진짜 객체를 끼운다 — 목이면 반영이 사라져 검증이 통과해도 아무것도 증명하지 못한다.
		MarketLineItemSyncDispatcher syncDispatcher = new MarketLineItemSyncDispatcher(orderLineItemRepository,
			new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository));
		service = new ElevenstOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, eventPublisher, adapter,
			syncStatusService, marketFeeService, terminalSettlementService, syncDispatcher,
			org.mockito.Mockito.mock(com.sbshop.agent.core.domain.order.repository.ShipmentRepository.class),
			marketRegistrationRepository);

		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(Optional.of(credential));

		when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
			Order o = inv.getArgument(0);
			if (o.getId() == null) {
				ReflectionTestUtils.setField(o, "id", 42L);
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
		when(marketFeeService.settlementAmount(any(), any()))
			.thenAnswer(inv -> inv.getArgument(0) == null ? BigDecimal.ZERO
				: ((BigDecimal)inv.getArgument(0)).multiply(new BigDecimal("0.82")));
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of());
	}

	private MarketLineItemDto item(String seq, String sbCode, String name, String amount,
		ShippingStatus status) {
		return MarketLineItemDto.builder()
			.marketLineItemNo(seq).marketProductCode(sbCode).productName(name)
			.quantity(1).orderPrice(new BigDecimal(amount)).totalAmount(new BigDecimal(amount))
			.status(status).marketSpecificData(new java.util.HashMap<>(Map.of("ordPrdSeq", seq)))
			.build();
	}

	private MarketShipmentDto shipment(String no, String tracking, MarketLineItemDto... items) {
		return MarketShipmentDto.builder()
			.marketShipmentNo(no)
			.trackingNo(tracking)
			.carrier(tracking == null ? null : ShippingCarrier.CJ_LOGISTICS)
			.lineItems(List.of(items))
			.build();
	}

	private MarketOrderDto orderDto(MarketShipmentDto... shipments) {
		return MarketOrderDto.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(ORD_NO)
			.recipientName("정나영")
			.zipcode("07997")
			.address("서울특별시 양천구 101동")
			.orderDate(LocalDateTime.of(2026, 7, 31, 0, 0))
			.marketSpecificData(new java.util.HashMap<>(Map.of("dlvNo", "2716448228", "ordPrdSeqs", "1|2")))
			.shipments(List.of(shipments))
			.build();
	}

	private void stubProduct(String sbCode, Long productId) {
		Product p = mock(Product.class);
		when(p.getId()).thenReturn(productId);
		when(productRepository.findBySbCode(sbCode)).thenReturn(Optional.of(p));
	}

	private void runSync(MarketOrderDto dto) {
		when(adapter.fetchOrdersWithOutcome(any(), any(), any()))
			.thenReturn(com.sbshop.agent.core.application.order.dto.MarketFetchOutcome.complete(List.of(dto)));
		service.syncElevenstOrders();
	}

	private OrderLineItem saved(String seq) {
		return savedLineItems.stream().filter(li -> seq.equals(li.getMarketLineItemNo()))
			.findFirst().orElseThrow(() -> new AssertionError("상품주문 " + seq + " 저장 안 됨"));
	}

	@Test
	@DisplayName("신규 다품목 주문은 상품주문마다 라인아이템을 만든다 — 2번째부터 유실되지 않는다")
	void createsOneLineItemPerProductOrder() {
		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.empty());
		stubProduct("210121IHB011", 312L);
		stubProduct("210121IHB012", 999L);

		runSync(orderDto(
			shipment("2716448228", null, item("1", "210121IHB011", "칼마", "57700", ShippingStatus.NEW)),
			shipment("2716448229", "424079080471",
				item("2", "210121IHB012", "뉴트리언트", "52800", ShippingStatus.SHIPPED))));

		assertThat(savedLineItems).hasSize(2);
		assertThat(saved("1").getProductId()).isEqualTo(312L);
		assertThat(saved("1").getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(saved("2").getProductId()).isEqualTo(999L);
		assertThat(saved("2").getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
		// 정산액은 상품주문별 금액으로 각각 계산된다 — 종전엔 순번1 금액만 있었다.
		assertThat(saved("1").getSettlementData().getSettlementAmount()).isEqualByComparingTo("47314.00");
		assertThat(saved("2").getSettlementData().getSettlementAmount()).isEqualByComparingTo("43296.00");
	}

	@Test
	@DisplayName("송장은 해당 배송의 라인아이템에만 붙는다 — 순번1은 송장 없이 남는다")
	void trackingAttachesOnlyToItsOwnShipment() {
		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.empty());
		stubProduct("210121IHB011", 312L);
		stubProduct("210121IHB012", 999L);

		runSync(orderDto(
			shipment("2716448228", null, item("1", "210121IHB011", "칼마", "57700", ShippingStatus.NEW)),
			shipment("2716448229", "424079080471",
				item("2", "210121IHB012", "뉴트리언트", "52800", ShippingStatus.SHIPPED))));

		assertThat(saved("2").getShippingData().getTrackingNo()).isEqualTo("424079080471");
		assertThat(saved("2").getShippingData().getTrackingSentToMarket()).isTrue();
		// 종전엔 순번2의 송장이 유일한 행에 붙어 순번1의 송장처럼 보였다.
		assertThat(saved("1").getShippingData().getTrackingNo()).isNull();
	}

	@Test
	@DisplayName("라인아이템이 배송에 연결된다 — 발송처리가 배송 단위로 나가려면 필수")
	void linksLineItemsToShipments() {
		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.empty());
		stubProduct("210121IHB011", 312L);

		runSync(orderDto(shipment("2716448228", null,
			item("1", "210121IHB011", "칼마", "57700", ShippingStatus.NEW))));

		assertThat(saved("1").getShipmentId()).isNotNull();
	}

	@Test
	@DisplayName("정나영 건: 기존 1행이 2행으로 갈릴 때 소싱·구매정보가 상품이 맞는 행에 남는다")
	void preservesOurOwnDataWhenSplitting() {
		// 이것이 2단계의 핵심 위험이다. 레거시 행(market_line_item_no NULL)이 채택되지 않으면
		// 소싱처·실구매가·구매상태가 아무도 보지 않는 고아 행에 남는다(D-132).
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem legacy = OrderLineItem.builder()
			.orderId(42L).quantity(1).productId(312L)
			.purchaseStatus(PurchaseStatus.PURCHASED)
			.sourcingData(SourcingData.builder().sourcingOrderNo("343911144").build())
			.shippingData(ShippingData.builder().trackingNo("424079080471").build())
			.build();
		ReflectionTestUtils.setField(legacy, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(legacy)));
		stubProduct("210121IHB011", 312L);
		stubProduct("210121IHB012", 999L);

		runSync(orderDto(
			shipment("2716448228", null, item("1", "210121IHB011", "칼마", "57700", ShippingStatus.NEW)),
			shipment("2716448229", "424079080471",
				item("2", "210121IHB012", "뉴트리언트", "52800", ShippingStatus.SHIPPED))));

		// 레거시 행이 상품 ID가 맞는 순번1로 채택되고 키가 부여된다.
		assertThat(legacy.getMarketLineItemNo()).isEqualTo("1");
		assertThat(legacy.getSourcingData().getSourcingOrderNo()).isEqualTo("343911144");
		assertThat(legacy.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
		assertThat(legacy.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
		// 섞인 흔적은 지우지 않는다(설계 5.4-3, D-125). 이 행의 송장은 실제로는 순번2의 것이었지만
		// 어느 쪽이 맞는지 자동으로 알 수 없으므로 보존하고 ⚠ 확인 필요로 로그에 남긴다.
		// 순번1이 속한 배송에는 송장이 없어 미러가 덮지도, 지우지도 않는다.
		assertThat(legacy.getShippingData().getTrackingNo()).isEqualTo("424079080471");

		// 순번2는 신규 생성 — 구매정보가 없는 것이 정확하다.
		OrderLineItem created = saved("2");
		assertThat(created.getId()).isNotEqualTo(459L);
		assertThat(created.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
	}

	@Test
	@DisplayName("단일 상품 기존 주문은 라인아이템이 늘지 않는다 — 현재 240행 회귀")
	void doesNotDuplicateForSingleItemOrders() {
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem legacy = OrderLineItem.builder().orderId(42L).quantity(1).productId(312L).build();
		ReflectionTestUtils.setField(legacy, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(legacy)));
		stubProduct("210121IHB011", 312L);

		runSync(orderDto(shipment("2716448228", null,
			item("1", "210121IHB011", "칼마", "57700", ShippingStatus.NEW))));

		assertThat(savedLineItems).containsExactly(legacy);
		assertThat(legacy.getMarketLineItemNo()).isEqualTo("1");
	}

	@Test
	@DisplayName("UNKNOWN 상태는 기존 상태를 덮지 않는다")
	void unknownStatusDoesNotOverwrite() {
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem legacy = OrderLineItem.builder()
			.orderId(42L).quantity(1).productId(312L).marketLineItemNo("1")
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.PREPARING).build())
			.build();
		ReflectionTestUtils.setField(legacy, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(legacy)));
		stubProduct("210121IHB011", 312L);

		runSync(orderDto(shipment("2716448228", null,
			item("1", "210121IHB011", "칼마", "57700", ShippingStatus.UNKNOWN))));

		// 새 상태명이 등장했을 때 배송중 주문이 신규로 되돌아가는 것이 가장 나쁜 실패다.
		assertThat(legacy.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("식별자 없는 라인아이템(배송중 목록 전용 주문)도 기존 행에 반영된다")
	void handlesUnidentifiedLineItem() {
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem legacy = OrderLineItem.builder().orderId(42L).quantity(1).productId(312L).build();
		ReflectionTestUtils.setField(legacy, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(legacy)));

		MarketLineItemDto unidentified = MarketLineItemDto.builder()
			.marketLineItemNo(null).quantity(0)
			.orderPrice(BigDecimal.ZERO).totalAmount(BigDecimal.ZERO)
			.status(ShippingStatus.SHIPPED).build();
		runSync(orderDto(shipment("2716448228", "363082000865", unidentified)));

		// 키를 위조하지 않으므로(D-131) 카디널리티로 매칭되고(D-132) 중복이 생기지 않는다.
		assertThat(savedLineItems).containsExactly(legacy);
		assertThat(legacy.getMarketLineItemNo()).isNull();
		assertThat(legacy.getShippingData().getTrackingNo()).isEqualTo("363082000865");
	}

	@Test
	@DisplayName("상품을 식별할 수 없으면 분할을 미룬다 — 빈 껍데기 행과 고아 행을 만들지 않는다")
	void defersSplitWhenProductUnidentifiable() {
		// 2026-08-06 라이브에서 실제로 터진 형태다. 정나영 건은 전체 정보 목록의 날짜 창을 지나
		// 배송중 목록에만 남아 있었고, 11번가는 orderlistall·orderlistalladdr 어느 쪽에도
		// sellerPrdCd를 주지 않는다. 그래서 상품 신호가 없는데도 2행을 만들어, 상품·금액이 빈
		// 행 두 개가 생기고 소싱정보가 붙은 옛 행이 고아가 됐다.
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem legacy = OrderLineItem.builder()
			.orderId(42L).quantity(1).productId(312L)
			.purchaseStatus(PurchaseStatus.PURCHASED)
			.sourcingData(SourcingData.builder().sourcingOrderNo("344142016").build())
			.build();
		ReflectionTestUtils.setField(legacy, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(legacy)));

		// 판매자상품코드가 없는 상품주문 2건 — 상품 매핑 불가.
		MarketLineItemDto noCode1 = MarketLineItemDto.builder()
			.marketLineItemNo("1").quantity(1)
			.orderPrice(new BigDecimal("57700")).totalAmount(new BigDecimal("57700"))
			.status(ShippingStatus.SHIPPED).build();
		MarketLineItemDto noCode2 = MarketLineItemDto.builder()
			.marketLineItemNo("2").quantity(1)
			.orderPrice(new BigDecimal("52800")).totalAmount(new BigDecimal("52800"))
			.status(ShippingStatus.SHIPPED).build();

		runSync(orderDto(shipment("2716448228", "315399495342", noCode1, noCode2)));

		// 아무 행도 만들지 않는다. 소싱정보는 옛 행에 그대로 있고 고아가 되지 않는다.
		assertThat(savedLineItems).isEmpty();
		assertThat(legacy.getSourcingData().getSourcingOrderNo()).isEqualTo("344142016");
		assertThat(legacy.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
		// 기존 행에 상품주문번호를 사람이 지정해 주면 다음 동기화에서 정확히 갈린다(정확키 매칭).
		assertThat(legacy.getMarketLineItemNo()).isNull();
	}

	@Test
	@DisplayName("상품을 식별할 수 있으면 정상 분할한다 — 가드가 정상 경로를 막지 않는다")
	void guardDoesNotBlockIdentifiableSplit() {
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem legacy = OrderLineItem.builder().orderId(42L).quantity(1).productId(312L).build();
		ReflectionTestUtils.setField(legacy, "id", 459L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(legacy)));
		stubProduct("210121IHB011", 312L);
		stubProduct("210121IHB012", 999L);

		runSync(orderDto(
			shipment("2716448228", null, item("1", "210121IHB011", "칼마", "57700", ShippingStatus.NEW)),
			shipment("2716448229", "424079080471",
				item("2", "210121IHB012", "뉴트리언트", "52800", ShippingStatus.SHIPPED))));

		assertThat(legacy.getMarketLineItemNo()).isEqualTo("1");
		assertThat(saved("2")).isNotNull();
	}

	@Test
	@DisplayName("마켓 실측 정산액이 있으면 요율 추정을 쓰지 않는다")
	void prefersActualSettlementAmount() {
		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.empty());
		stubProduct("210121IHB011", 312L);

		MarketLineItemDto withActual = MarketLineItemDto.builder()
			.marketLineItemNo("1").marketProductCode("210121IHB011").quantity(1)
			.orderPrice(new BigDecimal("57700")).totalAmount(new BigDecimal("57700"))
			.settlementAmount(new BigDecimal("49887"))
			.status(ShippingStatus.SHIPPED).build();
		runSync(orderDto(shipment("2716448228", "315399495342", withActual)));

		// 요율 추정(57700 x 0.82 = 47314)이 아니라 마켓이 준 49887을 쓴다.
		assertThat(saved("1").getSettlementData().getSettlementAmount()).isEqualByComparingTo("49887");
	}

	/** D-161: prdNo → sb_market_registration → sb_product_id 폴백을 위한 등록 스텁. */
	private void stubRegistration(String prdNo, Long productId) {
		com.sbshop.agent.core.domain.market.MarketRegistration reg = mock(
			com.sbshop.agent.core.domain.market.MarketRegistration.class);
		when(reg.getSbProductId()).thenReturn(productId);
		when(marketRegistrationRepository.findByMarketTypeAndIdentifiersContaining(
			MarketType.ELEVEN_STREET, prdNo)).thenReturn(List.of(reg));
	}

	@Test
	@DisplayName("D-161: sellerPrdCd가 없어도 prdNo로 상품을 해석한다 — 정나영 순번2가 빈 채로 남지 않는다")
	void resolvesProductByMarketProductNumber() {
		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.empty());
		stubRegistration("6124097725", 2500L);

		MarketLineItemDto seq2 = MarketLineItemDto.builder()
			.marketLineItemNo("2").sellerProductId("6124097725")
			.productName("쏜리서치 베이직 뉴트리언트 투퍼데이 60캡슐")
			.quantity(1).totalAmount(new BigDecimal("52800"))
			.settlementAmount(new BigDecimal("45648"))
			.status(ShippingStatus.SHIPPED).build();
		runSync(orderDto(shipment("2716448228", "315399495342", seq2)));

		assertThat(saved("2").getProductId()).isEqualTo(2500L);
	}

	@Test
	@DisplayName("D-161: sellerPrdCd가 있으면 그것이 우선이다 — prdNo는 폴백일 뿐")
	void sellerProductCodeWinsOverMarketProductNumber() {
		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.empty());
		stubProduct("210121IHB011", 312L);

		MarketLineItemDto seq1 = MarketLineItemDto.builder()
			.marketLineItemNo("1").marketProductCode("210121IHB011").sellerProductId("6124097725")
			.quantity(1).totalAmount(new BigDecimal("57700"))
			.status(ShippingStatus.SHIPPED).build();
		runSync(orderDto(shipment("2716448228", "315399495342", seq1)));

		assertThat(saved("1").getProductId()).isEqualTo(312L);
		org.mockito.Mockito.verify(marketRegistrationRepository, org.mockito.Mockito.never())
			.findByMarketTypeAndIdentifiersContaining(any(), anyString());
	}

	@Test
	@DisplayName("D-161: 기존 행도 다음 동기화에서 prdNo로 상품이 채워진다 — 수동 교정이 필요 없다")
	void backfillsProductIdOnExistingLineItem() {
		Order existing = Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo(ORD_NO).build();
		ReflectionTestUtils.setField(existing, "id", 42L);
		OrderLineItem orphanSeq2 = OrderLineItem.builder().orderId(42L).quantity(1)
			.marketLineItemNo("2").build();
		ReflectionTestUtils.setField(orphanSeq2, "id", 474L);

		when(orderRepository.findByMarketOrderNo(ORD_NO)).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(new ArrayList<>(List.of(orphanSeq2)));
		stubRegistration("6124097725", 2500L);

		MarketLineItemDto seq2 = MarketLineItemDto.builder()
			.marketLineItemNo("2").sellerProductId("6124097725")
			.quantity(1).totalAmount(new BigDecimal("52800"))
			.status(ShippingStatus.SHIPPED).build();
		runSync(orderDto(shipment("2716448228", "315399495342", seq2)));

		assertThat(orphanSeq2.getProductId()).isEqualTo(2500L);
	}

}

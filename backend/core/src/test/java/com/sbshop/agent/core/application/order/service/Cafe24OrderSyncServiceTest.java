package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cafe24 주문 API → G마켓/옥션 주문 매핑/저장 검증.
 * order_place_id로 gmarket/auction만 처리하고(타마켓 스킵), 필드를 도메인에 정확히 채우는지.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24OrderSyncServiceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock private Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private ApplicationEventPublisher eventPublisher;
	@Mock private com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;
	@Mock private com.sbshop.agent.core.domain.order.repository.ShipmentRepository shipmentRepository;

	// 코드 기본요율(빈 정책 폴백)로 정산액을 계산하도록 실제 인스턴스 사용
	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private Cafe24OrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new Cafe24OrderSyncService(cafe24OrderApiPort, orderRepository,
			orderLineItemRepository, marketRegistrationRepository, eventPublisher, syncStatusService,
			marketFeeService, org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			org.mockito.Mockito.mock(Cafe24ShipmentTrackingLookup.class),
			// 4단계: 이 테스트들이 검증하는 것이 곧 골격이 하는 일이다 — 목으로 대체하면 라인아이템
			// 반영이 사라져 검증이 통과해도 아무것도 증명하지 못한다. 진짜 골격을 끼운다.
			new MarketLineItemSyncDispatcher(orderLineItemRepository,
				new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository)));
		lenient().when(shipmentRepository.findByOrderIdAndMarketShipmentNo(
			org.mockito.ArgumentMatchers.any(), anyString())).thenReturn(java.util.Optional.empty());
		lenient().when(shipmentRepository.save(org.mockito.ArgumentMatchers.any(
			com.sbshop.agent.core.domain.order.Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));
		lenient().when(orderLineItemRepository.findByShipmentId(
			org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
		lenient().when(marketRegistrationRepository.findByMarketTypeAndIdentifiersContaining(
			org.mockito.ArgumentMatchers.any(), anyString())).thenReturn(List.of());
	}

	private JsonNode ordersJson() throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20240711-0000001","order_place_id":"gmarket","order_place_name":"지마켓",
			   "order_date":"2024-07-11T12:00:00+09:00","market_order_no":"GM123",
			   "buyer":{"name":"홍길동","cellphone":"010-1111-2222",
			      "personal_customs_clearance_code":"P012345678901"},
			   "receivers":[{"name":"김수취","cellphone":"010-3333-4444","zipcode":"12345",
			      "address_full":"서울시 강남구 테헤란로","shipping_message":"문앞"}],
			   "items":[{"product_no":"7034","product_code":"P7034","product_name":"테스트상품",
			      "quantity":2,"payment_amount":"10000","order_status":"N10","tracking_no":""}]},
			  {"order_id":"CP-999","order_place_id":"coupang","order_date":"2024-07-11T12:00:00+09:00",
			   "buyer":{},"receivers":[],"items":[]}
			]}
			""";
		return MAPPER.readTree(json).path("orders");
	}

	/** 통관번호가 receivers 쪽 후보 키(clearance_code)로만 오는 케이스. */
	private JsonNode ordersJsonPcccOnReceiver() throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20240711-0000002","order_place_id":"auction","order_place_name":"옥션",
			   "order_date":"2024-07-11T12:00:00+09:00","market_order_no":"AU123",
			   "buyer":{"name":"홍길동","cellphone":"010-1111-2222"},
			   "receivers":[{"name":"김수취","cellphone":"010-3333-4444","zipcode":"12345",
			      "address_full":"서울시 강남구","clearance_code":"R098765432109"}],
			   "items":[{"product_no":"7034","quantity":1,"payment_amount":"5000","order_status":"N10"}]}
			]}
			""";
		return MAPPER.readTree(json).path("orders");
	}

	/**
	 * 실제 Cafe24 주문 API 응답 구조: 개인통관고유부호(PCCC)는 receivers[].clearance_information 에 담긴다.
	 * 동반 필드 clearance_information_type="C"는 개인통관고유부호 유형을 뜻한다(라이브 강연희 주문 20260715-0000010 확인).
	 */
	private JsonNode ordersJsonRealClearanceInformation() throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20260715-0000010","order_place_id":"gmarket","order_place_name":"G마켓",
			   "order_date":"2026-07-15T12:00:00+09:00","market_order_no":"4469254653",
			   "buyer":{"name":"강연희","cellphone":"010-2930-0502"},
			   "receivers":[{"name":"강연희","cellphone":"010-2930-0502","zipcode":"12345",
			      "address_full":"서울시 강남구","clearance_information_type":"C",
			      "clearance_information":"P180023584849"}],
			   "items":[{"product_no":"7034","quantity":1,"payment_amount":"5000","order_status":"N10"}]}
			]}
			""";
		return MAPPER.readTree(json).path("orders");
	}

	/** 통관번호 필드가 어디에도 없는 케이스(회귀: null 유지). */
	private JsonNode ordersJsonNoPccc() throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20240711-0000003","order_place_id":"gmarket","order_place_name":"지마켓",
			   "order_date":"2024-07-11T12:00:00+09:00","market_order_no":"GM999",
			   "buyer":{"name":"홍길동","cellphone":"010-1111-2222"},
			   "receivers":[{"name":"김수취","cellphone":"010-3333-4444","zipcode":"12345",
			      "address_full":"서울시 강남구"}],
			   "items":[{"product_no":"7034","quantity":1,"payment_amount":"5000","order_status":"N10"}]}
			]}
			""";
		return MAPPER.readTree(json).path("orders");
	}

	@Test
	@DisplayName("gmarket 주문은 GMARKET으로 저장하고, coupang 등 타마켓은 스킵한다")
	void mapsGmarketAndSkipsOthers() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenReturn(ordersJson());
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.empty());

		int processed = service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		assertThat(processed).isEqualTo(1); // gmarket 1건만, coupang 스킵
		// 조회는 마켓 원본번호(market_order_no)로 하며, Cafe24 order_id로는 조회하지 않는다
		verify(orderRepository, never()).findByMarketOrderNo("20240711-0000001");
		// coupang 주문은 조회조차 하지 않음
		verify(orderRepository, never()).findByMarketOrderNo("CP-999");

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		Order saved = orderCaptor.getValue();
		assertThat(saved.getMarketType()).isEqualTo(MarketType.GMARKET);
		// marketOrderNo는 마켓 원본번호(market_order_no=GM123)여야 하며 Cafe24 order_id가 아니다
		assertThat(saved.getMarketOrderNo()).isEqualTo("GM123");
		// Cafe24 order_id는 marketSpecificData의 cafe24_order_id로 보관(발주확인·취소 타깃용)
		assertThat(saved.getMarketSpecificDataMap().get("cafe24_order_id")).isEqualTo("20240711-0000001");
		assertThat(saved.getRecipientName()).isEqualTo("김수취");
		assertThat(saved.getRecipientPhone()).isEqualTo("010-3333-4444");
		assertThat(saved.getAddress()).isEqualTo("서울시 강남구 테헤란로");
		assertThat(saved.getOrdererName()).isEqualTo("홍길동");
		assertThat(saved.getCustomsData().getCustomsClearanceNo()).isEqualTo("P012345678901"); // buyer PCCC 추출

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		// 골격은 라인아이템을 생성 시 한 번, 배송 미러에서 한 번 더 저장한다(같은 엔티티).
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		OrderLineItem item = lastOf(itemCaptor);
		assertThat(item.getQuantity()).isEqualTo(2);
		// D-088: N10(상품준비중)=발주확인 전(신규주문) → NEW. 발주확인(acceptOrder)이 N20으로 올리므로 N10은 미확인.
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("통관번호가 receiver 후보 키(clearance_code)에만 있어도 추출해 저장한다")
	void extractsPcccFromReceiverFallback() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJsonPcccOnReceiver());
		when(orderRepository.findByMarketOrderNo("AU123")).thenReturn(Optional.empty());

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getCustomsData().getCustomsClearanceNo()).isEqualTo("R098765432109");
	}

	@Test
	@DisplayName("실제 Cafe24 필드: 통관번호가 receivers[].clearance_information에 오면 추출·저장한다(강연희 P180023584849)")
	void extractsPcccFromReceiverClearanceInformation() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJsonRealClearanceInformation());
		when(orderRepository.findByMarketOrderNo("4469254653")).thenReturn(Optional.empty());

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getCustomsData().getCustomsClearanceNo()).isEqualTo("P180023584849");
	}

	@Test
	@DisplayName("통관번호 필드가 없으면 customsClearanceNo는 null로 유지(회귀)")
	void keepsPcccNullWhenAbsent() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJsonNoPccc());
		when(orderRepository.findByMarketOrderNo("GM999")).thenReturn(Optional.empty());

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getCustomsData().getCustomsClearanceNo()).isNull();
	}

	@Test
	@DisplayName("D-075: 동기화 실패 시 SyncCompletedEvent errorMessage에 원인(root cause)이 담긴다")
	void surfacesRootCauseInFailureEvent() {
		// RestClient wrapping을 흉내: 최상위는 "Cafe24 API 호출 실패...", 원인은 재인증 필요 메시지.
		RuntimeException wrapped = new RuntimeException(
			"Cafe24 API 호출 실패",
			new IllegalStateException("Cafe24 access token 획득 실패 — 재인증이 필요합니다"));
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenThrow(wrapped);

		service.syncCafe24Orders();

		ArgumentCaptor<SyncCompletedEvent> eventCaptor = ArgumentCaptor.forClass(SyncCompletedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		SyncCompletedEvent event = eventCaptor.getValue();
		assertThat(event.isSuccess()).isFalse();
		// "Cafe24 API 호출 실패"만이 아니라 root cause(재인증 필요)가 함께 보여야 한다.
		assertThat(event.getErrorMessage()).contains("재인증");
	}

	@Test
	@DisplayName("업데이트 시 PCCC가 있으면 기존 주문에 반영, 없으면 기존값 보존")
	void updateReflectsPcccWhenPresentOnly() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJson());
		Order existing = Order.builder()
			.marketType(MarketType.GMARKET).marketOrderNo("GM123")
			.orderDate(java.time.LocalDateTime.now()).build();
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		assertThat(existing.getCustomsData().getCustomsClearanceNo()).isEqualTo("P012345678901");
		// D-SP-E: 기존 행도 marketSpecificData가 갱신돼 cafe24_order_id가 채워진다(레거시 보정)
		assertThat(existing.getMarketSpecificDataMap().get("cafe24_order_id")).isEqualTo("20240711-0000001");
	}

	private JsonNode ordersWithStatus(String status) throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20240711-0000009","order_place_id":"gmarket","order_place_name":"지마켓",
			   "order_date":"2024-07-11T12:00:00+09:00","market_order_no":"GM555",
			   "buyer":{"name":"홍길동"},
			   "receivers":[{"name":"김수취","address_full":"서울"}],
			   "items":[{"product_no":"7034","quantity":1,"payment_amount":"5000","order_status":"%s"}]}
			]}
			""".formatted(status);
		return MAPPER.readTree(json).path("orders");
	}

	private ShippingStatus statusForCode(String code) throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersWithStatus(code));
		when(orderRepository.findByMarketOrderNo("GM555")).thenReturn(Optional.empty());
		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());
		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		// 골격은 라인아이템을 생성 시 한 번, 배송 미러에서 한 번 더 저장한다(같은 엔티티).
		// 최종 상태를 보려면 마지막 캡처값을 쓴다.
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		return lastOf(itemCaptor).getShippingData().getShippingStatus();
	}

	@Test
	@DisplayName("D-088: N10은 NEW(상품준비중=발주확인 전/신규주문). 라이브 확증: 옥션 2566278285")
	void n10New() throws Exception {
		// acceptOrder가 N20으로 올리므로 N10은 미확인 상태 → NEW. 과거 PREPARING 오분류(D-088) 회귀.
		assertThat(statusForCode("N10")).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("N20은 PREPARING(발주확인 후)")
	void n20Preparing() throws Exception {
		assertThat(statusForCode("N20")).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("N21은 PREPARING(발주확인 후)")
	void n21Preparing() throws Exception {
		assertThat(statusForCode("N21")).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("N22는 PREPARING(발주확인 후)")
	void n22Preparing() throws Exception {
		assertThat(statusForCode("N22")).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("N30은 SHIPPED")
	void n30Shipped() throws Exception {
		assertThat(statusForCode("N30")).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("N40은 DELIVERED")
	void n40Delivered() throws Exception {
		assertThat(statusForCode("N40")).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("N00은 NEW(결제완료/신규)")
	void n00New() throws Exception {
		assertThat(statusForCode("N00")).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("persistOrder는 market_order_no로 기존 주문을 찾아 갱신한다(order_id 아님)")
	void updatesExistingFoundByMarketOrderNo() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenReturn(ordersJson());
		Order existing = Order.builder()
			.marketType(MarketType.GMARKET).marketOrderNo("GM123")
			.orderDate(java.time.LocalDateTime.now()).build();
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.of(existing));
		OrderLineItem li = OrderLineItem.builder()
			.orderId(1L).quantity(1)
			.shippingData(com.sbshop.agent.core.domain.order.vo.ShippingData.builder()
				.shippingStatus(ShippingStatus.NEW).build())
			.build();
		when(orderLineItemRepository.findByOrderId(org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(li));

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		// createOrder(신규 save)로 가지 않고 기존 행 갱신 경로를 타야 한다: 신규 주문 save는 없음
		verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
			ord -> ord != existing));
		// D-088: 라인아이템 상태가 N10 → NEW(발주확인 전/신규)로 갱신
		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
	}

	/** D-129: 마켓이 실송장을 준 주문(배송중). 동기화가 채택하면 마켓 보유로 마킹돼야 한다. */
	private JsonNode ordersWithMarketTracking(String trackingNo) throws Exception {
		String json = """
			{"orders":[
			  {"order_id":"20260805-0000001","order_place_id":"gmarket","order_place_name":"지마켓",
			   "order_date":"2026-08-05T12:00:00+09:00","market_order_no":"GM777",
			   "buyer":{"name":"홍길동"},
			   "receivers":[{"name":"김수취","address_full":"서울"}],
			   "items":[{"product_no":"7034","quantity":1,"payment_amount":"5000",
			      "order_status":"N30","tracking_no":"%s","shipping_company_code":"0006"}]}
			]}
			""".formatted(trackingNo);
		return MAPPER.readTree(json).path("orders");
	}

	@Test
	@DisplayName("[D-129] 마켓이 준 실송장을 채택하면 마켓 보유(trackingSentToMarket=true)로 마킹한다")
	void marketSourcedTrackingIsMarkedAsOwnedByMarket() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersWithMarketTracking("424410280092"));
		when(orderRepository.findByMarketOrderNo("GM777")).thenReturn(Optional.empty());

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		// 골격은 라인아이템을 생성 시 한 번, 배송 미러에서 한 번 더 저장한다(같은 엔티티).
		// 최종 상태를 보려면 마지막 캡처값을 쓴다.
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		ShippingData shipping = lastOf(itemCaptor).getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("424410280092");
		// 마켓이 알려준 송장 = 마켓이 보유한 송장. 화면의 "마켓 미반영" 경고가 이 건에 뜨면 안 된다.
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("[D-129] 자리표시자 송장은 채택도 마킹도 하지 않는다")
	void placeholderTrackingIsNotMarked() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersWithMarketTracking("00000000"));
		when(orderRepository.findByMarketOrderNo("GM777")).thenReturn(Optional.empty());

		service.fetchAndPersist(java.time.LocalDate.now().minusDays(7), java.time.LocalDate.now());

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		// 골격은 라인아이템을 생성 시 한 번, 배송 미러에서 한 번 더 저장한다(같은 엔티티).
		// 최종 상태를 보려면 마지막 캡처값을 쓴다.
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		ShippingData shipping = lastOf(itemCaptor).getShippingData();
		assertThat(shipping.getTrackingNo()).isNull();
		assertThat(shipping.getTrackingSentToMarket()).isNull();
	}

	/** 골격이 같은 엔티티를 여러 번 저장하므로 최종 상태는 마지막 캡처값이다. */
	private static OrderLineItem lastOf(ArgumentCaptor<OrderLineItem> captor) {
		List<OrderLineItem> all = captor.getAllValues();
		return all.get(all.size() - 1);
	}
}

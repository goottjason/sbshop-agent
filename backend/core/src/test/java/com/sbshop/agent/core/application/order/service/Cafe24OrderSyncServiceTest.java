package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.sbshop.agent.core.application.market.MarketRegistrationLookup;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.application.sync.SyncCounts;
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

@ExtendWith(MockitoExtension.class)
class Cafe24OrderSyncServiceTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private ShipmentRepository shipmentRepository;

	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private Cafe24OrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new Cafe24OrderSyncService(cafe24OrderApiPort, orderRepository,
			orderLineItemRepository, new MarketRegistrationLookup(marketRegistrationRepository),
			eventPublisher, syncStatusService,
			marketFeeService,
			Mockito.mock(TerminalSettlementService.class),
			Mockito.mock(Cafe24ShipmentTrackingLookup.class),
			new MarketLineItemSyncDispatcher(orderLineItemRepository,
				new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository)));
		lenient().when(shipmentRepository.findByOrderIdAndMarketShipmentNo(
			ArgumentMatchers.any(), anyString())).thenReturn(Optional.empty());
		lenient().when(shipmentRepository.save(ArgumentMatchers.any(
			Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));
		lenient().when(orderLineItemRepository.findByShipmentId(
			ArgumentMatchers.any())).thenReturn(List.of());
		lenient().when(marketRegistrationRepository.findIdentifierCandidates(
			ArgumentMatchers.any(), anyString())).thenReturn(List.of());
	}

	@Test
	@DisplayName("신규 생성 건수를 처리 건수와 갈라서 돌려준다 — 유입 단절을 0건 성공과 구분한다")
	void countsCreatedSeparatelyFromProcessed() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJson());
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.empty());

		SyncCounts counts = service.fetchAndPersistWithCounts(
			LocalDate.now().minusDays(7), LocalDate.now(), true);

		assertThat(counts.processed()).isEqualTo(1);
		assertThat(counts.created()).isEqualTo(1);
	}

	@Test
	@DisplayName("이미 있는 주문만 갱신한 회차는 처리 1건·신규 0건이다")
	void existingOrderCountsAsProcessedNotCreated() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJson());
		Order existing = Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo("GM123")
			.build();
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.of(existing));

		SyncCounts counts = service.fetchAndPersistWithCounts(
			LocalDate.now().minusDays(7), LocalDate.now(), true);

		assertThat(counts.processed()).isEqualTo(1);
		assertThat(counts.created()).isZero();
	}

	@Test
	@DisplayName("gmarket 주문은 GMARKET으로 저장하고, coupang 등 타마켓은 스킵한다")
	void mapsGmarketAndSkipsOthers() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenReturn(ordersJson());
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.empty());

		int processed = service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		assertThat(processed).isEqualTo(1);
		verify(orderRepository, never()).findByMarketOrderNo("20240711-0000001");
		verify(orderRepository, never()).findByMarketOrderNo("CP-999");

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		Order saved = orderCaptor.getValue();
		assertThat(saved.getMarketType()).isEqualTo(MarketType.GMARKET);
		assertThat(saved.getMarketOrderNo()).isEqualTo("GM123");
		assertThat(saved.getMarketSpecificDataMap().get("cafe24_order_id")).isEqualTo("20240711-0000001");
		assertThat(saved.getRecipientName()).isEqualTo("김수취");
		assertThat(saved.getRecipientPhone()).isEqualTo("010-3333-4444");
		assertThat(saved.getAddress()).isEqualTo("서울시 강남구 테헤란로");
		assertThat(saved.getOrdererName()).isEqualTo("홍길동");
		assertThat(saved.getCustomsData().getCustomsClearanceNo()).isEqualTo("P012345678901");

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		OrderLineItem item = lastOf(itemCaptor);
		assertThat(item.getQuantity()).isEqualTo(2);
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("통관번호가 receiver 후보 키(clearance_code)에만 있어도 추출해 저장한다")
	void extractsPcccFromReceiverFallback() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJsonPcccOnReceiver());
		when(orderRepository.findByMarketOrderNo("AU123")).thenReturn(Optional.empty());

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

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

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

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

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getCustomsData().getCustomsClearanceNo()).isNull();
	}

	@Test
	@DisplayName("D-075: 동기화 실패 시 SyncCompletedEvent errorMessage에 원인(root cause)이 담긴다")
	void surfacesRootCauseInFailureEvent() {
		RuntimeException wrapped = new RuntimeException(
			"Cafe24 API 호출 실패",
			new IllegalStateException("Cafe24 access token 획득 실패 — 재인증이 필요합니다"));
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenThrow(wrapped);

		service.syncCafe24Orders();

		ArgumentCaptor<SyncCompletedEvent> eventCaptor = ArgumentCaptor.forClass(SyncCompletedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		SyncCompletedEvent event = eventCaptor.getValue();
		assertThat(event.isSuccess()).isFalse();
		assertThat(event.getErrorMessage()).contains("재인증");
	}

	@Test
	@DisplayName("업데이트 시 PCCC가 있으면 기존 주문에 반영, 없으면 기존값 보존")
	void updateReflectsPcccWhenPresentOnly() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersJson());
		Order existing = Order.builder()
			.marketType(MarketType.GMARKET).marketOrderNo("GM123")
			.orderDate(LocalDateTime.now()).build();
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(ArgumentMatchers.any())).thenReturn(List.of());

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		assertThat(existing.getCustomsData().getCustomsClearanceNo()).isEqualTo("P012345678901");
		assertThat(existing.getMarketSpecificDataMap().get("cafe24_order_id")).isEqualTo("20240711-0000001");
	}

	@Test
	@DisplayName("D-088: N10은 NEW(상품준비중=발주확인 전/신규주문). 라이브 확증: 옥션 2566278285")
	void n10New() throws Exception {
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
			.orderDate(LocalDateTime.now()).build();
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.of(existing));
		OrderLineItem li = OrderLineItem.builder()
			.orderId(1L).quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.NEW).build())
			.build();
		when(orderLineItemRepository.findByOrderId(ArgumentMatchers.any()))
			.thenReturn(List.of(li));

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		verify(orderRepository, never()).save(ArgumentMatchers.argThat(
			ord -> ord != existing));
		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("[D-129] 마켓이 준 실송장을 채택하면 마켓 보유(trackingSentToMarket=true)로 마킹한다")
	void marketSourcedTrackingIsMarkedAsOwnedByMarket() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersWithMarketTracking("424410280092"));
		when(orderRepository.findByMarketOrderNo("GM777")).thenReturn(Optional.empty());

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		ShippingData shipping = lastOf(itemCaptor).getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("424410280092");
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("[D-129] 자리표시자 송장은 채택도 마킹도 하지 않는다")
	void placeholderTrackingIsNotMarked() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0)))
			.thenReturn(ordersWithMarketTracking("00000000"));
		when(orderRepository.findByMarketOrderNo("GM777")).thenReturn(Optional.empty());

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		ShippingData shipping = lastOf(itemCaptor).getShippingData();
		assertThat(shipping.getTrackingNo()).isNull();
		assertThat(shipping.getTrackingSentToMarket()).isNull();
	}

	@Test
	@DisplayName("[D-218] G마켓 주문 4477134670 재현: product_no가 SB코드 안에 파묻힌 다른 상품을 집지 않는다")
	void resolvesLineItemProductByExactProductNoNotSubstring() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenReturn(ordersJson());
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.empty());
		when(marketRegistrationRepository.findIdentifierCandidates(MarketType.CAFE24, "7034"))
			.thenReturn(List.of(
				registration(999L, "{\"product_no\":\"20\",\"custom_product_code\":\"200828TE7034\"}"),
				registration(500L, "{\"product_no\": \"7034\", \"custom_product_code\": \"230806IHB130\"}")));

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		assertThat(lastOf(itemCaptor).getProductId()).isEqualTo(500L);
	}

	@Test
	@DisplayName("[D-218] 같은 product_no를 가진 등록행이 2건이면 상품을 붙이지 않고 비워 둔다")
	void leavesProductUnresolvedWhenProductNoIsAmbiguous() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(anyString(), anyString(), eq(100), eq(0))).thenReturn(ordersJson());
		when(orderRepository.findByMarketOrderNo("GM123")).thenReturn(Optional.empty());
		when(marketRegistrationRepository.findIdentifierCandidates(MarketType.CAFE24, "7034"))
			.thenReturn(List.of(
				registration(601L, "{\"product_no\":\"7034\"}"),
				registration(602L, "{\"product_no\": \"7034\"}")));

		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());

		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		assertThat(lastOf(itemCaptor).getProductId()).isNull();
	}

	private MarketRegistration registration(Long sbProductId, String identifiers) {
		return MarketRegistration.builder()
			.productId(sbProductId)
			.sbProductId(sbProductId)
			.marketType(MarketType.CAFE24)
			.marketIdentifiers(identifiers)
			.build();
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
		service.fetchAndPersist(LocalDate.now().minusDays(7), LocalDate.now());
		ArgumentCaptor<OrderLineItem> itemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository, atLeastOnce()).save(itemCaptor.capture());
		return lastOf(itemCaptor).getShippingData().getShippingStatus();
	}

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

	private static OrderLineItem lastOf(ArgumentCaptor<OrderLineItem> captor) {
		List<OrderLineItem> all = captor.getAllValues();
		return all.get(all.size() - 1);
	}
}

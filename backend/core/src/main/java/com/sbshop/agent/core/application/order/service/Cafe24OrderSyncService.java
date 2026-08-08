package com.sbshop.agent.core.application.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.common.RootCauseExtractor;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cafe24 주문 API로 G마켓/옥션 주문을 동기화한다(ESM+ Selenium 대체).
 * order_place_id로 마켓을 구분해 GMARKET/AUCTION으로 저장 → 통합주문관리에 G마켓/옥션으로 표시.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Cafe24OrderSyncService {

	// Cafe24 주문 API는 start_date/end_date를 날짜(yyyy-MM-dd)로 받는다(시간 포함 시 422 Invalid date format).
	private static final DateTimeFormatter CAFE24_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final int PAGE = 100;

	private final Cafe24OrderApiPort cafe24OrderApiPort;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final SyncStatusService syncStatusService;
	private final MarketFeeService marketFeeService;
	private final TerminalSettlementService terminalSettlementService;
	private final Cafe24ShipmentTrackingLookup shipmentTrackingLookup;
	/** 4단계: 3계층 반영 공통 골격. 마켓별 차이는 {@code syncPolicy}가 흡수한다. */
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCafe24Orders() {
		syncCafe24Orders(30);
	}

	/**
	 * 조회 기간(일)을 지정한 동기화. 기본 경로는 30일이고, <b>과거 구간 백필</b>에만 넓게 쓴다.
	 *
	 * <p>배경(2026-08-08): `market_tracking_no`(마켓 보유 송장)는 D-148에서 신설됐다. 그전에 30일 창을
	 * 벗어난 주문들은 이 값을 가질 기회가 없었고, 그래서 화면이 반영 여부를 판정하지 못했다
	 * (창 안 주문은 전 마켓 100% 수집되고 있었다 — 진행 중 동작은 이미 일관됐다).
	 * 새 경로를 만들지 않고 <b>검증된 동기화 경로를 넓은 기간으로 한 번 더 돌리는</b> 방식을 쓴다.
	 */
	@Transactional
	public void syncCafe24Orders(int lookbackDays) {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[CAFE24-ORDER] 동기화 중복 실행 방지");
			return;
		}
		// F-SYNC-2: 상태 기록을 async 스레드(이 본문) 안에서 수행 — 스케줄러가 조기 markCompleted 하던 문제 해소.
		syncStatusService.markRunning(SyncMarketKeys.GMARKET);
		boolean success = false;
		try {
			int count = fetchAndPersist(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
			// D-098: 취소·반품 종결 lineItem 정산0 정규화(멱등). Cafe24 경로는 G마켓·옥션 두 마켓을 담으므로 둘 다.
			terminalSettlementService.zeroSettlementForRefunded(MarketType.GMARKET);
			terminalSettlementService.zeroSettlementForRefunded(MarketType.AUCTION);
			log.info("[CAFE24-ORDER] G마켓/옥션 주문 동기화 완료: {}건", count);
			success = true;
			syncStatusService.markCompleted(SyncMarketKeys.GMARKET);
		} catch (Exception e) {
			String reason = failureReason(e);
			log.error("[CAFE24-ORDER] 동기화 실패: {}", reason, e);
			syncStatusService.markFailed(SyncMarketKeys.GMARKET, reason);
			eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.GMARKET, false, reason));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.GMARKET));
			}
		}
	}

	/**
	 * 실패 이벤트에 담을 사유 문자열을 만든다. wrapping 예외의 최상위 메시지에 더해, 그와 다른
	 * 최심 root cause 메시지가 있으면 결합해 원인이 은폐되지 않게 한다("Cafe24 API 호출 실패"만 뜨는 문제 해소).
	 */
	private String failureReason(Throwable e) {
		String top = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		String rootMsg = RootCauseExtractor.rootMessage(e);
		if (rootMsg != null && !rootMsg.isBlank() && !top.contains(rootMsg)) {
			return top + " (원인: " + rootMsg + ")";
		}
		return top;
	}

	/** 페이지네이션으로 전 주문을 순회하며 저장. G마켓/옥션(order_place_id)만 처리한다. */
	@Transactional
	public int fetchAndPersist(LocalDate from, LocalDate to) {
		String start = from.format(CAFE24_DT);
		String end = to.format(CAFE24_DT);
		int offset = 0;
		int processed = 0;
		while (offset <= 15000) {
			JsonNode orders = cafe24OrderApiPort.fetchOrders(start, end, PAGE, offset);
			if (orders == null || !orders.isArray() || orders.isEmpty()) {
				break;
			}
			for (JsonNode o : orders) {
				if (persistOrder(o)) {
					processed++;
				}
			}
			if (orders.size() < PAGE) {
				break;
			}
			offset += PAGE;
		}
		return processed;
	}

	/** @return G마켓/옥션 주문으로 실제 저장/갱신했으면 true, 스킵했으면 false. */
	private boolean persistOrder(JsonNode o) {
		MarketType marketType = mapMarket(o.path("order_place_id").asText(""));
		if (marketType == null) {
			return false; // Cafe24에 연동된 오픈마켓(G마켓/옥션) 외 주문은 스킵(직접몰·타마켓 중복 방지)
		}
		String marketOrderNo = resolveMarketOrderNo(o);
		if (marketOrderNo.isBlank()) {
			return false;
		}
		Optional<Order> existing = orderRepository.findByMarketOrderNo(marketOrderNo);
		if (existing.isPresent()) {
			updateOrder(existing.get(), o, marketType);
		} else {
			createOrder(o, marketType);
		}
		return true;
	}

	private void createOrder(JsonNode o, MarketType marketType) {
		JsonNode receiver = firstOf(o.path("receivers"));
		JsonNode buyer = o.path("buyer");
		String pccc = extractPccc(buyer, receiver, o);

		Order order = Order.builder()
			.marketType(marketType)
			.marketOrderNo(resolveMarketOrderNo(o))
			.orderDate(parseDate(o.path("order_date").asText(null)))
			.recipientName(text(receiver, "name"))
			.recipientPhone(firstNonBlank(text(receiver, "cellphone"), text(receiver, "phone")))
			.zipcode(text(receiver, "zipcode"))
			.address(receiverAddress(receiver))
			.message(text(receiver, "shipping_message"))
			.customsData(pccc != null ? CustomsData.builder().customsClearanceNo(pccc).build() : null)
			.ordererName(firstNonBlank(text(buyer, "name"), text(o, "order_place_name")))
			.ordererPhone(firstNonBlank(text(buyer, "cellphone"), text(buyer, "phone")))
			.marketSpecificData(buildMarketSpecific(o))
			.build();
		orderRepository.save(order);
		lineItemSyncDispatcher.sync(order, toNestedDto(o, order, marketType), List.of(), syncPolicy);
		log.info("[CAFE24-ORDER] 신규 저장: market={}, orderId={}", marketType, order.getMarketOrderNo());
	}

	private void updateOrder(Order order, JsonNode o, MarketType marketType) {
		JsonNode receiver = firstOf(o.path("receivers"));
		JsonNode buyer = o.path("buyer");

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		// 진행(PREPARING 이상) 라인아이템이 있으면 주소·우편번호를 API 값으로 덮지 않음(수기 보정 보호, 세트 — D-074)
		boolean protectAddress = lineItems.stream().anyMatch(OrderLineItem::isProgressed);
		order.update(
			text(receiver, "name"),
			firstNonBlank(text(receiver, "cellphone"), text(receiver, "phone")),
			protectAddress ? null : text(receiver, "zipcode"),
			protectAddress ? null : receiverAddress(receiver),
			text(receiver, "shipping_message"),
			firstNonBlank(text(buyer, "name"), text(o, "order_place_name")),
			firstNonBlank(text(buyer, "cellphone"), text(buyer, "phone")),
			marketType);
		// 통관번호는 실값일 때만 반영한다 — 마켓은 배송중·배송완료에서 이 필드를 빼거나 마스킹해 주고,
		// 한 번 지워지면 마켓에서 되받을 수 없어 복구가 불가능하다(도메인 가드가 정본).
		order.applyCustomsClearanceNoFromMarket(extractPccc(buyer, receiver, o));
		// 기존 행에도 cafe24_order_id를 채워 발주확인·취소가 Cafe24 order_id로 타깃하게 한다(마켓번호 전환 대응)
		refreshMarketSpecific(order, o);
		orderRepository.save(order);

		// D-119: 배송상태와 함께 송장도 갱신한다. 종전에는 상태만 갱신하고 트래킹은 "마켓 전송 가드가 있는
		// 별도 경로"에 맡겼는데, 그 경로는 우리가 송장을 보낼 때만 동작한다. 마켓(G마켓/옥션) 화면에서
		// 직접 입력된 송장은 어디로도 들어오지 못해, 배송완료인데 송장이 비어 있는 주문이 생겼다.
		// 마켓이 진실 원본이므로 마켓 값이 실값일 때만 반영하고, 빈 값/자리표시자로는 덮지 않는다.
		// 4단계: 배열 인덱스 짝짓기를 걷어냈다. items[] 원소가 order_item_code(상품주문)와
		// shipping_code(배송)를 직접 갖고 있어 식별자로 정확히 짝지을 수 있다(2026-08-06 라이브 확인).
		// 종전에는 개수가 같을 때 인덱스로 짝짓고, 다르면 첫 아이템 상태를 전체에 씌웠다 —
		// 마켓이 순서를 바꾸면 엉뚱한 상품에 송장·상태가 붙었다.
		lineItemSyncDispatcher.sync(order, toNestedDto(o, order, marketType), lineItems, syncPolicy);
	}

	/**
	 * 이 주문을 3계층 DTO로 조립한다. 주문 공통 필드는 이미 {@code order}에 반영됐으므로
	 * 여기서는 <b>배송·상품주문 계층</b>만 담는다.
	 */
	private MarketOrderDto toNestedDto(JsonNode o, Order order, MarketType marketType) {
		List<MarketShipmentDto> shipments =
			Cafe24LineItemMapper.toShipments(o, order.getMarketOrderNo());
		enrichTrackingFromShipmentList(shipments, order.getCafe24OrderId());

		return MarketOrderDto.builder()
			.marketType(marketType)
			.marketOrderNo(order.getMarketOrderNo())
			.shipments(shipments)
			.build();
	}

	/**
	 * D-124: 주문 item에 자리표시자만 비칠 때 배송건 목록에서 실송장을 찾아 채운다.
	 *
	 * <p>ESM+ 자체배송은 Cafe24에 {@code '00000000'} 더미만 등록된다(라이브 확증). 배송이
	 * <b>하나뿐일 때만</b> 적용한다 — 여러 배송 중 아무 곳에나 붙이면 엉뚱한 송장이 들어간다.
	 * 발송 전 상태거나 이미 실송장을 갖고 있으면 호출하지 않는다(불필요한 API 호출 억제).
	 */
	private void enrichTrackingFromShipmentList(List<MarketShipmentDto> shipments, String cafe24OrderId) {
		if (shipments.size() != 1) {
			return;
		}
		MarketShipmentDto only = shipments.get(0);
		if (ShippingData.isMeaningfulTracking(only.getTrackingNo()) || !hasShippedItem(only)) {
			return;
		}
		Cafe24ShipmentTrackingLookup.Found found = shipmentTrackingLookup.findRealTracking(cafe24OrderId);
		if (found == null) {
			return;
		}
		only.setTrackingNo(found.trackingNo());
		if (found.carrier() != null) {
			only.setCarrier(found.carrier());
		}
	}

	/** 발송 이후 상태의 상품주문이 하나라도 있는지. */
	private boolean hasShippedItem(MarketShipmentDto shipment) {
		if (shipment.getLineItems() == null) {
			return false;
		}
		return shipment.getLineItems().stream().anyMatch(li -> {
			ShippingStatus s = li.getStatus();
			return s == ShippingStatus.DISPATCHED || s == ShippingStatus.SHIPPED
				|| s == ShippingStatus.DELIVERED;
		});
	}

	/**
	 * 이 마켓의 3계층 동기화 정책. 골격이 갖지 않는 세 가지만 구현한다 —
	 * 로그 태그·상품 해석·라인아이템 생성(정산액 산출).
	 */
	private final MarketLineItemSyncPolicy syncPolicy = new MarketLineItemSyncPolicy() {
		@Override
		public String logTag() {
			return "CAFE24-ORDER";
		}

		@Override
		public Long resolveProductId(MarketLineItemDto dto) {
			return cafe24ResolveProductId(dto);
		}

		@Override
		public OrderLineItem createLineItem(MarketLineItemDto dto, Long orderId, Long productId) {
			// Cafe24는 G마켓/옥션 주문의 동기화 매개체 — 요율은 세 마켓 동일(18%)이라 CAFE24 기준 1회 적용.
			BigDecimal settlement =
				marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.CAFE24);
			return OrderLineItem.builder()
				.orderId(orderId)
				.productId(productId)
				.quantity(dto.getQuantity() != null ? dto.getQuantity() : 1)
				.marketLineItemNo(dto.getMarketLineItemNo())
				// 송장은 넣지 않는다 — 배송이 단일 원본이고 미러가 내려쓴다(D-133).
				.shippingData(ShippingData.builder()
					.shippingStatus(dto.getStatus())
					.build())
				.settlementData(SettlementData.builder()
					.settlementAmount(settlement)
					.settlementVerified(false)
					.build())
				.build();
		}
	};

	/** Cafe24 상품(product_no/product_code)로 sb 상품 매핑(카페24 마켓등록에 저장돼 있음). */
	private Long cafe24ResolveProductId(MarketLineItemDto dto) {
		Map<String, Object> data = dto.getMarketSpecificData();
		if (data == null) {
			return null;
		}
		for (String key : new String[] {"product_no", "product_code"}) {
			Object raw = data.get(key);
			if (raw == null || String.valueOf(raw).isBlank()) {
				continue;
			}
			List<MarketRegistration> regs = marketRegistrationRepository
				.findByMarketTypeAndIdentifiersContaining(MarketType.CAFE24, String.valueOf(raw));
			if (!regs.isEmpty()) {
				return regs.get(0).getSbProductId();
			}
		}
		return null;
	}

	/**
	 * 개인통관고유부호(PCCC)를 buyer/receiver/order 노드에서 추출한다.
	 * 실제 Cafe24 주문 Admin API는 PCCC를 receivers[].clearance_information 에 담는다(동반 필드
	 * clearance_information_type="C"가 개인통관고유부호 유형 — 라이브 주문 20260715-0000010으로 확정, 2026-07-17).
	 * clearance_information을 1순위로 두고, 레거시/변형 대비 알려진 후보 키들을 buyer→receiver→order 순으로
	 * 순차 시도한다. 모두 blank면 null(기존값 미변경)을 반환하고, 못 찾은 경우 노드의 key 목록을 debug로 남긴다.
	 * PII 보호: 통관번호 값 자체는 info로 평문 로깅하지 않는다.
	 */
	private static final String[] PCCC_KEYS = {
		"clearance_information", "personal_customs_clearance_code", "personal_customs_code",
		"customs_clearance_code", "clearance_code", "customs_no", "personal_customs_number", "pccc"
	};

	private String extractPccc(JsonNode buyer, JsonNode receiver, JsonNode order) {
		for (JsonNode node : new JsonNode[] {buyer, receiver, order}) {
			for (String key : PCCC_KEYS) {
				String v = text(node, key);
				if (v != null && !v.isBlank()) {
					return v.trim();
				}
			}
		}
		if (log.isDebugEnabled()) {
			log.debug("[CAFE24-ORDER] PCCC 미검출 orderId={} buyerKeys={} receiverKeys={}",
				order.path("order_id").asText(""), fieldNames(buyer), fieldNames(receiver));
		}
		return null;
	}

	private String fieldNames(JsonNode node) {
		if (node == null || !node.isObject()) {
			return "[]";
		}
		StringBuilder sb = new StringBuilder("[");
		node.fieldNames().forEachRemaining(n -> sb.append(n).append(","));
		return sb.append("]").toString();
	}

	// ── 매핑/파싱 유틸 ──

	/** order_place_id → 마켓. Cafe24에 연동된 오픈마켓(G마켓/옥션)만 매핑, 그 외는 null(스킵). */
	private MarketType mapMarket(String orderPlaceId) {
		if (orderPlaceId == null) {
			return null;
		}
		return switch (orderPlaceId.toLowerCase()) {
			case "gmarket" -> MarketType.GMARKET;
			case "auction" -> MarketType.AUCTION;
			default -> null;
		};
	}

	private LocalDateTime parseDate(String s) {
		if (s == null || s.isBlank()) {
			return LocalDateTime.now();
		}
		try {
			// Cafe24는 오프셋 포함 ISO(예: 2024-07-11T12:00:00+09:00) → 로컬(KST 벽시계)로 저장
			return OffsetDateTime.parse(s).toLocalDateTime();
		} catch (Exception ignore) {
			try {
				return LocalDateTime.parse(s);
			} catch (Exception e) {
				return LocalDateTime.now();
			}
		}
	}

	private String receiverAddress(JsonNode receiver) {
		String full = text(receiver, "address_full");
		if (full != null && !full.isBlank()) {
			return full;
		}
		String a1 = text(receiver, "address1");
		String a2 = text(receiver, "address2");
		return firstNonBlank(a1, "") + (a2 != null && !a2.isBlank() ? " " + a2 : "");
	}

	/**
	 * 화면 표시·유니크키로 쓸 마켓 원본 주문번호. 오픈마켓(G마켓/옥션)은 항상 market_order_no가 있으며,
	 * 없을 때만 Cafe24 order_id로 방어적 폴백한다(레거시/이상 데이터 대비).
	 */
	private String resolveMarketOrderNo(JsonNode o) {
		String marketOrderNo = o.path("market_order_no").asText("");
		return !marketOrderNo.isBlank() ? marketOrderNo : o.path("order_id").asText("");
	}

	private String buildMarketSpecific(JsonNode o) {
		return String.format(
			"{\"order_place_id\":\"%s\",\"order_place_name\":\"%s\",\"market_order_no\":\"%s\",\"cafe24_order_id\":\"%s\"}",
			o.path("order_place_id").asText(""), o.path("order_place_name").asText(""),
			o.path("market_order_no").asText(""), o.path("order_id").asText(""));
	}

	/** updateOrder에서 기존 행의 marketSpecificData를 갱신해 cafe24_order_id를 채운다(레거시 행 보정). */
	private void refreshMarketSpecific(Order order, JsonNode o) {
		java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
		map.put("order_place_id", o.path("order_place_id").asText(""));
		map.put("order_place_name", o.path("order_place_name").asText(""));
		map.put("market_order_no", o.path("market_order_no").asText(""));
		map.put("cafe24_order_id", o.path("order_id").asText(""));
		order.setMarketSpecificDataFromMap(map);
	}

	private JsonNode firstOf(JsonNode array) {
		return (array != null && array.isArray() && array.size() > 0) ? array.get(0) : null;
	}

	private String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asText(null);
	}

	private String firstNonBlank(String a, String b) {
		return (a != null && !a.isBlank()) ? a : b;
	}

	private BigDecimal decimal(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(s);
		} catch (Exception e) {
			return null;
		}
	}
}

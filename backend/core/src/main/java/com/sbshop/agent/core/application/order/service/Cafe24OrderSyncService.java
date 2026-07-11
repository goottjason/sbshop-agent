package com.sbshop.agent.core.application.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
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

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCafe24Orders() {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[CAFE24-ORDER] 동기화 중복 실행 방지");
			return;
		}
		boolean success = false;
		try {
			int count = fetchAndPersist(LocalDate.now().minusDays(30), LocalDate.now());
			log.info("[CAFE24-ORDER] G마켓/옥션 주문 동기화 완료: {}건", count);
			success = true;
		} catch (Exception e) {
			log.error("[CAFE24-ORDER] 동기화 실패: {}", e.getMessage(), e);
			eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.GMARKET, false, e.getMessage()));
		} finally {
			isSyncing.set(false);
			if (success) {
				eventPublisher.publishEvent(new SyncCompletedEvent(this, MarketType.GMARKET));
			}
		}
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
		String orderId = o.path("order_id").asText("");
		if (orderId.isBlank()) {
			return false;
		}
		Optional<Order> existing = orderRepository.findByMarketOrderNo(orderId);
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
			.marketOrderNo(o.path("order_id").asText())
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

		for (JsonNode item : o.path("items")) {
			orderLineItemRepository.save(buildLineItem(item, order.getId()));
		}
		log.info("[CAFE24-ORDER] 신규 저장: market={}, orderId={}", marketType, order.getMarketOrderNo());
	}

	private void updateOrder(Order order, JsonNode o, MarketType marketType) {
		JsonNode receiver = firstOf(o.path("receivers"));
		JsonNode buyer = o.path("buyer");

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
		// 진행(PREPARING 이상) 라인아이템이 있으면 주소를 API 값으로 덮지 않음(수기 보정 보호)
		boolean protectAddress = lineItems.stream().anyMatch(OrderLineItem::isProgressed);
		order.update(
			text(receiver, "name"),
			firstNonBlank(text(receiver, "cellphone"), text(receiver, "phone")),
			text(receiver, "zipcode"),
			protectAddress ? null : receiverAddress(receiver),
			text(receiver, "shipping_message"),
			firstNonBlank(text(buyer, "name"), text(o, "order_place_name")),
			firstNonBlank(text(buyer, "cellphone"), text(buyer, "phone")),
			null,
			marketType);
		// 통관번호는 non-blank일 때만 반영(기존값 보존). blank면 갱신하지 않음.
		String pccc = extractPccc(buyer, receiver, o);
		if (pccc != null) {
			order.updateCustomsClearanceNo(pccc);
		}
		orderRepository.save(order);

		// 배송상태만 갱신(트래킹은 마켓 전송 가드가 있는 별도 경로에서 관리)
		JsonNode firstItem = firstOf(o.path("items"));
		ShippingStatus status = mapStatus(text(firstItem, "order_status"));
		for (OrderLineItem item : lineItems) {
			item.applyShippingData(item.getShippingData().toBuilder().shippingStatus(status).build());
			orderLineItemRepository.save(item);
		}
	}

	private OrderLineItem buildLineItem(JsonNode item, Long orderId) {
		Long productId = resolveProductId(item);
		BigDecimal itemAmount = decimal(firstNonBlank(text(item, "payment_amount"), text(item, "product_price")));
		int qty = item.path("quantity").asInt(1);
		BigDecimal total = itemAmount != null ? itemAmount.multiply(BigDecimal.valueOf(qty)) : null;
		BigDecimal settlement = total != null ? total.multiply(new BigDecimal("0.89")) : null;

		return OrderLineItem.builder()
			.orderId(orderId)
			.productId(productId)
			.quantity(qty)
			.shippingData(ShippingData.builder()
				.trackingNo(text(item, "tracking_no"))
				.shippingStatus(mapStatus(text(item, "order_status")))
				.build())
			.settlementData(SettlementData.builder()
				.settlementAmount(settlement)
				.settlementVerified(false)
				.build())
			.build();
	}

	/** Cafe24 상품(product_no/product_code)로 sb 상품 매핑(카페24 마켓등록에 저장돼 있음). */
	private Long resolveProductId(JsonNode item) {
		String productNo = text(item, "product_no");
		String productCode = text(item, "product_code");
		for (String key : new String[] {productNo, productCode}) {
			if (key == null || key.isBlank()) {
				continue;
			}
			List<MarketRegistration> regs = marketRegistrationRepository
				.findByMarketTypeAndIdentifiersContaining(MarketType.CAFE24, key);
			if (!regs.isEmpty()) {
				return regs.get(0).getSbProductId();
			}
		}
		return null;
	}

	/**
	 * 개인통관고유부호(PCCC)를 buyer/receiver/order 노드에서 방어적으로 추출한다.
	 * Cafe24 주문 API의 정확한 PCCC 필드명이 문서로 100% 확정되지 않아, 알려진 후보 키들을
	 * buyer→receiver→order 순으로 순차 시도한다. 모두 blank면 null(기존값 미변경)을 반환하고,
	 * 못 찾은 경우 노드의 key 목록을 debug로 남겨 라이브 preview에서 실제 필드명을 확정할 수 있게 한다.
	 * PII 보호: 통관번호 값 자체는 info로 평문 로깅하지 않는다.
	 */
	private static final String[] PCCC_KEYS = {
		"personal_customs_clearance_code", "personal_customs_code", "customs_clearance_code",
		"clearance_code", "customs_no", "personal_customs_number", "pccc"
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

	/** Cafe24 order_status(N/C/R/E 코드) → 도메인 ShippingStatus. */
	private ShippingStatus mapStatus(String code) {
		if (code == null || code.isBlank()) {
			return ShippingStatus.NEW;
		}
		String c = code.toUpperCase();
		if (c.startsWith("C")) {
			return ShippingStatus.CANCELED;
		}
		if (c.startsWith("R")) {
			return ShippingStatus.RETURNED;
		}
		if (c.startsWith("E")) {
			return ShippingStatus.EXCHANGED;
		}
		return switch (c) {
			case "N00", "N10" -> ShippingStatus.NEW;
			case "N20", "N21", "N22" -> ShippingStatus.PREPARING;
			case "N30" -> ShippingStatus.SHIPPED;
			case "N40", "N50" -> ShippingStatus.DELIVERED;
			default -> ShippingStatus.NEW;
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

	private String buildMarketSpecific(JsonNode o) {
		return String.format("{\"order_place_id\":\"%s\",\"order_place_name\":\"%s\",\"market_order_no\":\"%s\"}",
			o.path("order_place_id").asText(""), o.path("order_place_name").asText(""),
			o.path("market_order_no").asText(""));
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

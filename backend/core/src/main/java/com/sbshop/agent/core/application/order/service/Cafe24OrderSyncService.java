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
import java.util.LinkedHashMap;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class Cafe24OrderSyncService {
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
	private final MarketLineItemSyncDispatcher lineItemSyncDispatcher;

	private final AtomicBoolean isSyncing = new AtomicBoolean(false);

	@Async("syncTaskExecutor")
	@Transactional
	public void syncCafe24Orders() {
		syncCafe24Orders(30);
	}

	public void syncCafe24Orders(int lookbackDays) {
		syncCafe24Orders(LocalDate.now().minusDays(lookbackDays), LocalDate.now());
	}

	public void syncCafe24Orders(LocalDate fromDate, LocalDate toDate) {
		syncCafe24Orders(fromDate, toDate, true);
	}

	@Transactional
	public void syncCafe24Orders(LocalDate fromDate, LocalDate toDate, boolean createMissing) {
		if (!isSyncing.compareAndSet(false, true)) {
			log.warn("[CAFE24-ORDER] 동기화 중복 실행 방지");
			return;
		}
		syncStatusService.markRunning(SyncMarketKeys.GMARKET);
		boolean success = false;
		try {
			int count = fetchAndPersist(fromDate, toDate, createMissing);
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

	@Transactional
	public int fetchAndPersist(LocalDate from, LocalDate to) {
		return fetchAndPersist(from, to, true);
	}

	public int fetchAndPersist(LocalDate from, LocalDate to, boolean createMissing) {
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
				if (persistOrder(o, createMissing)) {
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

	private String failureReason(Throwable e) {
		String top = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		String rootMsg = RootCauseExtractor.rootMessage(e);
		if (rootMsg != null && !rootMsg.isBlank() && !top.contains(rootMsg)) {
			return top + " (원인: " + rootMsg + ")";
		}
		return top;
	}

	private boolean persistOrder(JsonNode o, boolean createMissing) {
		MarketType marketType = mapMarket(o.path("order_place_id").asText(""));
		if (marketType == null) {
			return false;
		}
		String marketOrderNo = resolveMarketOrderNo(o);
		if (marketOrderNo.isBlank()) {
			return false;
		}
		Optional<Order> existing = orderRepository.findByMarketOrderNo(marketOrderNo);
		if (existing.isPresent()) {
			updateOrder(existing.get(), o, marketType);
		} else if (createMissing) {
			createOrder(o, marketType);
		} else {
			log.debug("[CAFE24-ORDER] 갱신 전용 모드 — 없는 주문은 만들지 않는다: orderNo={}", marketOrderNo);
			return false;
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

	private MarketOrderDto toNestedDto(JsonNode o, Order order, MarketType marketType) {
		List<MarketShipmentDto> shipments = Cafe24LineItemMapper.toShipments(o, order.getMarketOrderNo());
		enrichTrackingFromShipmentList(shipments, order.getCafe24OrderId());

		return MarketOrderDto.builder()
			.marketType(marketType)
			.marketOrderNo(order.getMarketOrderNo())
			.shipments(shipments)
			.build();
	}

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
			BigDecimal settlement = marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.CAFE24);
			return OrderLineItem.builder()
				.orderId(orderId)
				.productId(productId)
				.quantity(dto.getQuantity() != null ? dto.getQuantity() : 1)
				.marketLineItemNo(dto.getMarketLineItemNo())
				.shippingData(ShippingData.builder()
					.shippingStatus(dto.getStatus())
					.build())
				.settlementData(SettlementData.builder()
					.settlementAmount(settlement)
					.settlementVerified(false)
					.build())
				.build();
		}

		@Override
		public BigDecimal settlementAmount(MarketLineItemDto dto) {
			return marketFeeService.settlementAmount(dto.getTotalAmount(), MarketType.CAFE24);
		}
	};

	private String fieldNames(JsonNode node) {
		if (node == null || !node.isObject()) {
			return "[]";
		}
		StringBuilder sb = new StringBuilder("[");
		node.fieldNames().forEachRemaining(n -> sb.append(n).append(","));
		return sb.append("]").toString();
	}

	private static final String[] PCCC_KEYS = {
		"clearance_information", "personal_customs_clearance_code", "personal_customs_code",
		"customs_clearance_code", "clearance_code", "customs_no", "personal_customs_number", "pccc"
	};

	private String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asText(null);
	}

	private LocalDateTime parseDate(String s) {
		if (s == null || s.isBlank()) {
			return LocalDateTime.now();
		}
		try {
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

	private String firstNonBlank(String a, String b) {
		return (a != null && !a.isBlank()) ? a : b;
	}

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

	private JsonNode firstOf(JsonNode array) {
		return (array != null && array.isArray() && array.size() > 0) ? array.get(0) : null;
	}

	private void updateOrder(Order order, JsonNode o, MarketType marketType) {
		JsonNode receiver = firstOf(o.path("receivers"));
		JsonNode buyer = o.path("buyer");

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(order.getId());
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
		order.applyCustomsClearanceNoFromMarket(extractPccc(buyer, receiver, o));
		refreshMarketSpecific(order, o);
		orderRepository.save(order);

		lineItemSyncDispatcher.sync(order, toNestedDto(o, order, marketType), lineItems, syncPolicy);
	}

	private void refreshMarketSpecific(Order order, JsonNode o) {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("order_place_id", o.path("order_place_id").asText(""));
		map.put("order_place_name", o.path("order_place_name").asText(""));
		map.put("market_order_no", o.path("market_order_no").asText(""));
		map.put("cafe24_order_id", o.path("order_id").asText(""));
		order.setMarketSpecificDataFromMap(map);
	}

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

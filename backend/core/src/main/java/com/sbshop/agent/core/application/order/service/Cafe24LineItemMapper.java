package com.sbshop.agent.core.application.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Cafe24LineItemMapper {
	private Cafe24LineItemMapper() {}

	static List<MarketShipmentDto> toShipments(JsonNode order, String fallbackShipmentNo) {
		Map<String, List<MarketLineItemDto>> byShippingCode = new LinkedHashMap<>();
		Map<String, String> trackingByCode = new LinkedHashMap<>();
		Map<String, ShippingCarrier> carrierByCode = new LinkedHashMap<>();

		JsonNode items = order.path("items");
		if (items.isArray()) {
			for (JsonNode item : items) {
				String shippingCode = text(item, "shipping_code");
				if (shippingCode == null) {
					shippingCode = fallbackShipmentNo;
				}
				byShippingCode.computeIfAbsent(shippingCode, k -> new ArrayList<>()).add(toLineItem(item));

				String tracking = text(item, "tracking_no");
				if (ShippingData.isMeaningfulTracking(tracking)) {
					trackingByCode.putIfAbsent(shippingCode, tracking);
					ShippingCarrier carrier = ShippingCarrier.resolve(
						text(item, "shipping_company_code"), text(item, "shipping_company_name"));
					if (carrier != null) {
						carrierByCode.putIfAbsent(shippingCode, carrier);
					}
				}
			}
		}

		if (byShippingCode.isEmpty()) {
			log.warn("[CAFE24-ORDER] items가 비어 있다: orderNo={} — 식별자 없는 라인아이템 1건으로 처리",
				fallbackShipmentNo);
			byShippingCode.put(fallbackShipmentNo, List.of(MarketLineItemDto.builder()
				.quantity(0)
				.orderPrice(BigDecimal.ZERO)
				.totalAmount(BigDecimal.ZERO)
				.status(ShippingStatus.UNKNOWN)
				.build()));
		}

		List<MarketShipmentDto> shipments = new ArrayList<>();
		for (Map.Entry<String, List<MarketLineItemDto>> entry : byShippingCode.entrySet()) {
			shipments.add(MarketShipmentDto.builder()
				.marketShipmentNo(entry.getKey())
				.trackingNo(trackingByCode.get(entry.getKey()))
				.carrier(carrierByCode.get(entry.getKey()))
				.lineItems(entry.getValue())
				.build());
		}
		return shipments;
	}

	public static ShippingStatus mapStatus(String code) {
		if (code == null || code.isBlank()) {
			return ShippingStatus.UNKNOWN;
		}
		String c = code.trim().toUpperCase();
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
			case "N00", "N02", "N10" -> ShippingStatus.NEW;
			case "N20", "N21", "N22" -> ShippingStatus.PREPARING;
			case "N30" -> ShippingStatus.SHIPPED;
			case "N40", "N50" -> ShippingStatus.DELIVERED;
			default -> {
				log.warn("[CAFE24-ORDER] 미매핑 order_status 코드={} → UNKNOWN(상태를 덮지 않는다)", code);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}

	private static MarketLineItemDto toLineItem(JsonNode item) {
		int qty = item.path("quantity").asInt(1);
		BigDecimal unit = decimal(firstNonBlank(text(item, "payment_amount"), text(item, "product_price")));
		BigDecimal total = unit != null ? unit.multiply(BigDecimal.valueOf(qty)) : null;

		Map<String, Object> marketData = new HashMap<>();
		putIfPresent(marketData, "product_no", text(item, "product_no"));
		putIfPresent(marketData, "product_code", text(item, "product_code"));
		putIfPresent(marketData, "custom_product_code", text(item, "custom_product_code"));
		putIfPresent(marketData, "order_item_code", text(item, "order_item_code"));
		putIfPresent(marketData, "market_custom_variant_code", text(item, "market_custom_variant_code"));

		return MarketLineItemDto.builder()
			.marketLineItemNo(text(item, "order_item_code"))
			.productName(text(item, "product_name"))
			.quantity(qty)
			.orderPrice(unit)
			.totalAmount(total)
			.status(mapStatus(text(item, "order_status")))
			.marketSpecificData(marketData)
			.build();
	}

	private static void putIfPresent(Map<String, Object> map, String key, String value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.path(field);
		if (v.isMissingNode() || v.isNull()) {
			return null;
		}
		String s = v.asText();
		return (s == null || s.isBlank() || "null".equals(s)) ? null : s;
	}

	private static String firstNonBlank(String a, String b) {
		return (a != null && !a.isBlank()) ? a : b;
	}

	private static BigDecimal decimal(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}

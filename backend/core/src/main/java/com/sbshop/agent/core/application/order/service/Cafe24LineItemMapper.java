package com.sbshop.agent.core.application.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
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

/**
 * Cafe24 주문의 {@code items[]}를 (배송 / 상품주문) 2계층으로 변환한다.
 *
 * <h2>인덱스 짝짓기는 원래 필요가 없었다</h2>
 *
 * <p>2026-08-06 라이브 확인: 주문 {@code items[]} 원소가 <b>배송 식별자를 직접</b> 갖는다.
 * <pre>
 * order_item_code = 20260805-0000011-01     ← 상품주문 식별자
 * shipping_code   = D-20260805-0000011-00   ← 배송 식별자
 * order_status    = N20                     ← 상품별 진행상태
 * tracking_no · shipping_company_code       ← 상품별 송장·택배사
 * </pre>
 *
 * <p>종전 {@code Cafe24OrderSyncService.applyItemShipping}은 {@code items.size()}와 라인아이템
 * 개수가 같을 때 <b>배열 인덱스로</b> 짝지었고, 다르면 첫 아이템 상태를 전체에 씌웠다.
 * 마켓이 순서를 바꾸면 엉뚱한 상품에 송장·상태가 붙는다. {@code order_item_code}는 처음부터
 * 응답에 있었고 우리가 저장하지 않았을 뿐이다(기존 주석도 "order_item_code 미보존"이라 적고 있었다).
 *
 * <p>Cafe24는 네 마켓 중 매핑이 가장 깔끔하다 — 그룹핑에 {@code shipments} 리소스를 호출할 필요조차
 * 없다. (그 리소스는 D-124의 실송장 탐색에 계속 쓰인다.)
 */
@Slf4j
final class Cafe24LineItemMapper {

	private Cafe24LineItemMapper() {}

	/**
	 * {@code items[]}를 배송별로 묶어 돌려준다.
	 *
	 * @param fallbackShipmentNo 배송 식별자를 못 얻었을 때 쓸 값(주문번호). 설계 §3.3 —
	 *                           배송 계층은 항상 존재해야 상위 로직에 null 분기가 생기지 않는다
	 */
	static List<MarketShipmentDto> toShipments(JsonNode order, String fallbackShipmentNo) {
		// 배송 식별자 등장 순서를 유지한다 — 로그·디버깅에서 응답 순서와 대조하기 쉽다.
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

				// 송장·택배사는 배송의 것이다. 같은 배송의 아이템들이 같은 값을 갖지만, 실값을 준
				// 첫 아이템을 채택한다 — D-119: 자리표시자('00000000')로 실송장을 덮지 않는다.
				String tracking = text(item, "tracking_no");
				if (ShippingData.isMeaningfulTracking(tracking)) {
					trackingByCode.putIfAbsent(shippingCode, tracking);
					// 코드가 미매핑이면 이름으로 폴백한다 — Cafe24는 code='0006', name='CJ대한통운'처럼
					// 둘을 함께 주고, 종전엔 미매핑 코드가 매핑 가능한 이름을 가렸다(2026-08-06 라이브).
					ShippingCarrier carrier = ShippingCarrier.resolve(
						text(item, "shipping_company_code"), text(item, "shipping_company_name"));
					if (carrier != null) {
						carrierByCode.putIfAbsent(shippingCode, carrier);
					}
				}
			}
		}

		if (byShippingCode.isEmpty()) {
			// 상품이 없는 주문도 드롭하지 않는다(11번가 2단계의 교훈 — 조용히 사라진다).
			// 식별자는 위조하지 않으므로(D-131) 매칭은 카디널리티가 한다(D-132).
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

	private static MarketLineItemDto toLineItem(JsonNode item) {
		int qty = item.path("quantity").asInt(1);
		BigDecimal unit = decimal(firstNonBlank(text(item, "payment_amount"), text(item, "product_price")));
		BigDecimal total = unit != null ? unit.multiply(BigDecimal.valueOf(qty)) : null;

		// 상품 매핑은 product_no → product_code 순으로 market_registration을 뒤진다. 두 값이 모두
		// 필요하므로 마켓 데이터에 담아 넘긴다(정책이 읽는다).
		Map<String, Object> marketData = new java.util.HashMap<>();
		putIfPresent(marketData, "product_no", text(item, "product_no"));
		putIfPresent(marketData, "product_code", text(item, "product_code"));
		putIfPresent(marketData, "custom_product_code", text(item, "custom_product_code"));
		putIfPresent(marketData, "order_item_code", text(item, "order_item_code"));
		// 카페24 몰 상품과 연동이 끊긴 마켓 리스팅은 product_no=-99999, custom_product_code=null로
		// 온다(2026-08-12 라이브, 주문 4478251768). 그때 범인을 지목할 수 있는 유일한 단서가
		// 마켓 쪽 판매자 관리코드다 — 매핑에는 못 써도 미매핑 경고에는 실려야 한다.
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

	/**
	 * Cafe24 {@code order_status} 코드를 진행상태로 매핑한다.
	 *
	 * <p><b>모르는 코드는 {@code UNKNOWN}이다.</b> 종전 폴백은 {@code NEW}였는데, 새 코드가 등장하면
	 * 배송중 주문이 신규로 되돌아간다 — 가장 나쁜 실패다. 11번가·쿠팡은 이미 정리했고 Cafe24만
	 * 남아 있었다. 골격이 {@code UNKNOWN}으로 기존 상태를 덮지 않으므로 안전하게 보존된다.
	 */
	static ShippingStatus mapStatus(String code) {
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
			// D-088: N10(상품준비중)=발주확인 전(신규주문). 발주확인(acceptOrder)이 N20으로 올린다.
			case "N00", "N02", "N10" -> ShippingStatus.NEW;       // 입금전/주문접수중/상품준비중
			case "N20", "N21", "N22" -> ShippingStatus.PREPARING; // 배송준비중/배송대기/배송보류
			case "N30" -> ShippingStatus.SHIPPED;                 // 배송중
			case "N40", "N50" -> ShippingStatus.DELIVERED;        // 배송완료/구매확정
			default -> {
				log.warn("[CAFE24-ORDER] 미매핑 order_status 코드={} → UNKNOWN(상태를 덮지 않는다)", code);
				yield ShippingStatus.UNKNOWN;
			}
		};
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

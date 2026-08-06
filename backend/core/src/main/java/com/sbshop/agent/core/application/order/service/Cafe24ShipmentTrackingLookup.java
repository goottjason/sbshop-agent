package com.sbshop.agent.core.application.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D-124: Cafe24 배송건 목록에서 실제 송장을 찾는다.
 *
 * 주문 목록의 {@code items[].tracking_no}는 배송건이 여러 개일 때 하나만 비친다. G마켓/옥션에서
 * 송장을 등록·변경하면 Cafe24 주문 item에는 자체배송 자리표시자('00000000')만 남는 사례가
 * 확인돼(주문 20260719-0000018·20260730-0000016), 배송건 목록을 직접 뒤져 실값을 찾는다.
 *
 * 조회 실패는 삼킨다 — 송장 보강은 부가 기능이고, 이것 때문에 주문 동기화 전체가 깨지면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24ShipmentTrackingLookup {

	private final Cafe24OrderApiPort cafe24OrderApiPort;

	/** 배송건에서 찾은 실송장. */
	public record Found(String trackingNo, ShippingCarrier carrier) {
	}

	/**
	 * 해당 Cafe24 주문의 배송건 중 실값 송장을 가진 첫 건을 반환한다.
	 *
	 * @return 실값이 없거나 조회 실패면 null (호출부는 기존값을 유지해야 한다)
	 */
	public Found findRealTracking(String cafe24OrderId) {
		if (cafe24OrderId == null || cafe24OrderId.isBlank()) {
			return null;
		}

		JsonNode shipments;
		try {
			shipments = cafe24OrderApiPort.fetchShipments(cafe24OrderId);
		} catch (RuntimeException e) {
			log.warn("[Cafe24 배송건] 조회 실패 orderId={}: {}", cafe24OrderId, e.getMessage());
			return null;
		}

		if (shipments == null || !shipments.isArray() || shipments.isEmpty()) {
			log.debug("[Cafe24 배송건] 없음 orderId={}", cafe24OrderId);
			return null;
		}

		for (JsonNode shipment : shipments) {
			String trackingNo = text(shipment, "tracking_no");
			if (!ShippingData.isMeaningfulTracking(trackingNo)) {
				continue;
			}
			// 코드 미매핑 시 이름으로 폴백(2026-08-06 라이브: Cafe24 '0006' = CJ대한통운).
			ShippingCarrier carrier = ShippingCarrier.resolve(
				text(shipment, "shipping_company_code"), text(shipment, "shipping_company_name"));
			log.info("[Cafe24 배송건] 실송장 발견 orderId={}, tracking={}, carrier={}",
				cafe24OrderId, trackingNo, carrier);
			return new Found(trackingNo, carrier);
		}

		// 진단 가치가 큰 지점 — 배송건은 있는데 전부 자리표시자면 마켓→Cafe24 송장 연동이 끊긴 것이다.
		log.info("[Cafe24 배송건] orderId={} 배송건 {}건이 모두 자리표시자 — 마켓 실송장 미연동",
			cafe24OrderId, shipments.size());
		return null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asText();
	}

	private static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) {
			return a;
		}
		return b != null && !b.isBlank() ? b : null;
	}
}

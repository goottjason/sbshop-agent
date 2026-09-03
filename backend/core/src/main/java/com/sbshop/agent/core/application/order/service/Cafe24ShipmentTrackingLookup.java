package com.sbshop.agent.core.application.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24ShipmentTrackingLookup {
	private final Cafe24OrderApiPort cafe24OrderApiPort;

	public record Found(String trackingNo, ShippingCarrier carrier) {
	}

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
			ShippingCarrier carrier = ShippingCarrier.resolve(
				text(shipment, "shipping_company_code"), text(shipment, "shipping_company_name"),
				"CAFE24 배송건 orderId=" + cafe24OrderId);
			log.info("[Cafe24 배송건] 실송장 발견 orderId={}, tracking={}, carrier={}",
				cafe24OrderId, trackingNo, carrier);
			return new Found(trackingNo, carrier);
		}

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

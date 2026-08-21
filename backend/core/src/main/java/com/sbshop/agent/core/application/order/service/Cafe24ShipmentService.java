package com.sbshop.agent.core.application.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class Cafe24ShipmentService {
	private final Cafe24OrderApiPort cafe24OrderApiPort;

	private volatile Map<String, String> carrierNameToCode;

	public void ship(Order order, String trackingNo, ShippingCarrier carrier) {
		String orderId = order.getCafe24OrderId();
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalStateException("Cafe24 주문번호(order_id) 없음");
		}
		if (trackingNo == null || trackingNo.isBlank()) {
			throw new IllegalStateException("송장번호가 없어 Cafe24 배송 등록 불가");
		}
		String companyCode = resolveCarrierCode(carrier);

		String existingCode = resolveExistingShipmentCode(orderId);
		if (existingCode != null) {
			Map<String, Object> patch = new LinkedHashMap<>();
			patch.put("tracking_no", trackingNo);
			patch.put("shipping_company_code", companyCode);
			cafe24OrderApiPort.updateShipment(orderId, existingCode,
				Map.of("shop_no", 1, "request", patch));
			log.info("[Cafe24 송장] 배송건 수정 완료: orderId={}, shippingCode={}, tracking={}, carrierCode={}",
				orderId, existingCode, trackingNo, companyCode);
			return;
		}

		List<String> itemCodes = extractItemCodes(cafe24OrderApiPort.fetchOrderDetail(orderId));

		Map<String, Object> req = new LinkedHashMap<>();
		req.put("shop_no", 1);
		req.put("tracking_no", trackingNo);
		req.put("shipping_company_code", companyCode);
		req.put("status", "shipping");
		if (!itemCodes.isEmpty()) {
			req.put("order_item_code", itemCodes);
		}
		cafe24OrderApiPort.registerShipment(orderId, Map.of("request", req));
		log.info("[Cafe24 송장] 등록 완료: orderId={}, tracking={}, carrierCode={}, items={}",
			orderId, trackingNo, companyCode, itemCodes.size());
	}

	private String resolveExistingShipmentCode(String orderId) {
		JsonNode shipments = cafe24OrderApiPort.fetchShipments(orderId);
		if (shipments == null || !shipments.isArray() || shipments.isEmpty()) {
			return null;
		}
		List<String> editable = new ArrayList<>();
		for (JsonNode s : shipments) {
			String code = text(s, "shipping_code");
			if (code != null && !code.isBlank()
				&& ShippingData.isMeaningfulTracking(text(s, "tracking_no"))) {
				editable.add(code);
			}
		}
		if (editable.isEmpty()) {
			return null;
		}
		if (editable.size() > 1) {
			throw new IllegalStateException("Cafe24 배송건이 여러 개라 어느 것을 수정할지 알 수 없습니다: "
				+ "orderId=" + orderId + ", shipping_codes=" + editable);
		}
		return editable.get(0);
	}

	private String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asText(null);
	}

	private List<String> extractItemCodes(JsonNode orderDetail) {
		List<String> codes = new ArrayList<>();
		if (orderDetail != null) {
			for (JsonNode it : orderDetail.path("items")) {
				String c = text(it, "order_item_code");
				String statusCode = text(it, "status_code");
				boolean shippable = statusCode == null || statusCode.toUpperCase().startsWith("N");
				if (c != null && !c.isBlank() && shippable) {
					codes.add(c);
				}
			}
		}
		return codes;
	}

	private String resolveCarrierCode(ShippingCarrier carrier) {
		Map<String, String> map = carrierMap();
		if (map.isEmpty()) {
			throw new IllegalStateException("Cafe24 몰에 등록된 택배사가 없습니다(관리자에서 택배사 등록 필요).");
		}
		String label = carrier != null ? carrier.getLabel() : null;
		if (label != null) {
			String key = normalize(label);
			for (Map.Entry<String, String> e : map.entrySet()) {
				String name = normalize(e.getKey());
				if (name.contains(key) || key.contains(name)) {
					return e.getValue();
				}
			}
		}
		throw new IllegalStateException("Cafe24 택배사 코드 매칭 실패: carrier=" + label
			+ " (몰 등록 택배사=" + map.keySet() + ")");
	}

	private Map<String, String> carrierMap() {
		Map<String, String> cached = carrierNameToCode;
		if (cached != null) {
			return cached;
		}
		Map<String, String> m = new LinkedHashMap<>();
		JsonNode carriers = cafe24OrderApiPort.fetchCarriers();
		if (carriers != null) {
			for (JsonNode c : carriers) {
				String code = firstText(c, "shipping_company_code", "shipping_carrier_code", "carrier_id", "code");
				String name = firstText(c, "shipping_carrier", "shipping_carrier_name", "company_name", "name");
				if (code != null && name != null) {
					m.put(name, code);
				}
			}
		}
		carrierNameToCode = m;
		return m;
	}

	private String firstText(JsonNode node, String... fields) {
		for (String f : fields) {
			String v = text(node, f);
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}

	private String normalize(String s) {
		return s == null ? "" : s.replaceAll("[\\s()택배로지스틱스대한통운]", "").toUpperCase();
	}
}

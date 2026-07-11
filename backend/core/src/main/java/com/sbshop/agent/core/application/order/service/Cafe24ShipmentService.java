package com.sbshop.agent.core.application.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * G마켓/옥션(Cafe24 연동) 주문의 송장을 Cafe24 주문 API로 역전송한다.
 * POST /admin/orders/{order_id}/shipments — tracking_no, shipping_company_code, status, order_item_code.
 * 택배사 코드는 몰별이라 GET /admin/carriers로 조회해 우리 ShippingCarrier 라벨과 매칭한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Cafe24ShipmentService {

	private final Cafe24OrderApiPort cafe24OrderApiPort;

	// 몰 택배사(이름→코드) 캐시. 최초 1회 /carriers 조회.
	private volatile Map<String, String> carrierNameToCode;

	/** 송장 등록. order.marketOrderNo == Cafe24 order_id. */
	public void ship(Order order, String trackingNo, ShippingCarrier carrier) {
		String orderId = order.getMarketOrderNo();
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalStateException("Cafe24 주문번호(order_id) 없음");
		}
		if (trackingNo == null || trackingNo.isBlank()) {
			throw new IllegalStateException("송장번호가 없어 Cafe24 배송 등록 불가");
		}
		String companyCode = resolveCarrierCode(carrier);
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

	private List<String> extractItemCodes(JsonNode orderDetail) {
		List<String> codes = new ArrayList<>();
		if (orderDetail != null) {
			for (JsonNode it : orderDetail.path("items")) {
				String c = text(it, "order_item_code");
				// 취소/반품(status_code C*/R*)은 제외, 정상(N*)만 배송 등록
				String statusCode = text(it, "status_code");
				boolean shippable = statusCode == null || statusCode.toUpperCase().startsWith("N");
				if (c != null && !c.isBlank() && shippable) {
					codes.add(c);
				}
			}
		}
		return codes;
	}

	/** ShippingCarrier 라벨(예: "CJ대한통운")을 몰 등록 택배사명과 매칭해 코드 반환. */
	private String resolveCarrierCode(ShippingCarrier carrier) {
		Map<String, String> map = carrierMap();
		if (map.isEmpty()) {
			throw new IllegalStateException("Cafe24 몰에 등록된 택배사가 없습니다(관리자에서 택배사 등록 필요).");
		}
		String label = carrier != null ? carrier.getLabel() : null; // "CJ대한통운" 등
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

	private String normalize(String s) {
		return s == null ? "" : s.replaceAll("[\\s()택배로지스틱스대한통운]", "").toUpperCase();
	}

	private String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : v.asText(null);
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
}

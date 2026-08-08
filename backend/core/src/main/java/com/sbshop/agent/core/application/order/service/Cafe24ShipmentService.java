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

	/** 송장 등록. Cafe24 order_id는 order.getCafe24OrderId()(marketSpecific의 cafe24_order_id, 없으면 marketOrderNo 폴백)로 타깃한다. */
	public void ship(Order order, String trackingNo, ShippingCarrier carrier) {
		String orderId = order.getCafe24OrderId();
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalStateException("Cafe24 주문번호(order_id) 없음");
		}
		if (trackingNo == null || trackingNo.isBlank()) {
			throw new IllegalStateException("송장번호가 없어 Cafe24 배송 등록 불가");
		}
		String companyCode = resolveCarrierCode(carrier);

		// D-151: 이미 배송건이 있는 주문에는 새 배송건을 만들 수 없다
		// (라이브 2026-08-08: 422 "You cannot change to that order state" — 주문이 배송중 N1이고
		// 배송건 D-...-00이 더미 송장 00000000으로 이미 등록돼 있었다). 11번가·네이버와 달리
		// Cafe24에는 수정 경로가 있으므로 그 배송건의 송장을 고친다.
		String existingCode = resolveExistingShipmentCode(orderId);
		if (existingCode != null) {
			Map<String, Object> patch = new LinkedHashMap<>();
			patch.put("tracking_no", trackingNo);
			patch.put("shipping_company_code", companyCode);
			// status는 싣지 않는다 — 이미 배송중인 주문에 상태를 다시 보내면 같은 422를 부른다.
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

	/**
	 * 이미 등록된 배송건의 {@code shipping_code}. 없으면 {@code null}(신규 등록 경로로 간다).
	 *
	 * <p>여러 개면 <b>추측하지 않고 실패</b>한다 — 엉뚱한 배송건의 송장을 고치면 되돌리기 어렵고,
	 * 어느 배송건이 이 상품주문의 것인지 판단할 근거가 여기에는 없다(D-127과 같은 규율).
	 */
	private String resolveExistingShipmentCode(String orderId) {
		JsonNode shipments = cafe24OrderApiPort.fetchShipments(orderId);
		if (shipments == null || !shipments.isArray() || shipments.isEmpty()) {
			return null;
		}
		// 배송건의 "존재"가 아니라 <b>마켓이 실송장을 들고 있는지</b>가 수정/등록을 가른다.
		// Cafe24는 발송 전에도 배송건 D-...-00을 미리 만들어 둔다(2026-08-08 라이브 확인:
		// 아직 우리가 송장을 보내지 않은 20260807-0000011에도 배송건이 있었다). 존재만 보고
		// 수정으로 보내면 최초 전송이 전부 수정 경로로 새고, 마켓플레이스 주문은 수정이
		// 거부되므로(D-154) 송장이 영영 마켓에 못 들어간다.
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

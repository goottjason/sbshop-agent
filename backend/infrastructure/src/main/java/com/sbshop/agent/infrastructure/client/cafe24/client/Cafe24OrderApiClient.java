package com.sbshop.agent.infrastructure.client.cafe24.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cafe24 주문 Admin API 클라이언트. 기존 Cafe24RestClient(토큰 자동 관리)를 재사용한다.
 * 주문 조회에는 mall.read_order scope가 필요 — 토큰 재발급 시 scope에 포함돼야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24OrderApiClient implements Cafe24OrderApiPort {

	private final Cafe24RestClient restClient;
	private final ObjectMapper objectMapper;

	@Override
	public JsonNode fetchOrders(String startDate, String endDate, int limit, int offset) {
		String path = "/admin/orders?embed=items,receivers,buyer"
			+ "&date_type=order_date"
			+ "&start_date=" + enc(startDate)
			+ "&end_date=" + enc(endDate)
			+ "&limit=" + limit
			+ "&offset=" + offset;
		String response = restClient.get(path);
		try {
			return objectMapper.readTree(response).path("orders");
		} catch (Exception e) {
			log.error("[Cafe24 주문] 응답 파싱 실패: {}", e.getMessage());
			throw new RuntimeException("Cafe24 주문 응답 파싱 실패", e);
		}
	}

	@Override
	public JsonNode fetchOrderDetail(String orderId) {
		String response = restClient.get("/admin/orders/" + orderId + "?embed=items");
		try {
			return objectMapper.readTree(response).path("order");
		} catch (Exception e) {
			throw new RuntimeException("Cafe24 주문상세 파싱 실패", e);
		}
	}

	@Override
	public JsonNode fetchCarriers() {
		String response = restClient.get("/admin/carriers");
		try {
			return objectMapper.readTree(response).path("carriers");
		} catch (Exception e) {
			throw new RuntimeException("Cafe24 택배사 파싱 실패", e);
		}
	}

	@Override
	public String registerShipment(String orderId, Object requestBody) {
		return restClient.post("/admin/orders/" + orderId + "/shipments", requestBody);
	}

	// Cafe24 배송상태 처리 API(PUT /admin/orders): 쓰기는 process_status 문자열을 쓴다(읽기 order_status N코드와 별개).
	// prepare=배송준비중(발주확인), prepareproduct=상품준비중, hold=배송보류, unhold=배송보류해제.
	private static final String ACCEPT_PROCESS_STATUS = "prepare"; // 배송준비중(=발주확인)
	private static final String CANCEL_STATUS = "C40"; // 취소완료 — 취소는 별도 API 필요(D-091 후속 미검증)

	/**
	 * D-091: 발주확인. Cafe24 스펙은 PUT /admin/orders(경로에 id 없음)에
	 * requests 배열로 {order_id, process_status} 전송. 라인아이템 단위(order_item_code)는
	 * sbshop이 품주코드를 미보존하므로 생략 → 주문 전체에 적용.
	 */
	@Override
	public void acceptOrder(String cafe24OrderId) {
		java.util.Map<String, Object> request = java.util.Map.of(
			"order_id", cafe24OrderId,
			"process_status", ACCEPT_PROCESS_STATUS);
		java.util.Map<String, Object> body = java.util.Map.of(
			"shop_no", 1,
			"requests", java.util.List.of(request));
		restClient.put("/admin/orders", body);
		log.info("[Cafe24] 발주확인(배송준비중): orderId={}, process_status={}", cafe24OrderId, ACCEPT_PROCESS_STATUS);
	}

	@Override
	public void cancelOrder(String cafe24OrderId) {
		updateStatus(cafe24OrderId, CANCEL_STATUS);
	}

	private void updateStatus(String cafe24OrderId, String status) {
		java.util.Map<String, Object> body = java.util.Map.of(
			"shop_no", 1,
			"request", java.util.Map.of("status", status));
		restClient.put("/admin/orders/" + cafe24OrderId, body);
		log.info("[Cafe24] 주문상태 변경: orderId={}, status={}", cafe24OrderId, status);
	}

	private String enc(String s) {
		// 쿼리 값의 공백은 '+'가 아니라 %20으로(일부 서버가 '+'를 공백으로 해석하지 않음).
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}

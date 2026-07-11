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

	// Cafe24 주문 상태코드 — 라이브 검증 대상(값·필드명·바디형태 확정 필요).
	private static final String ACCEPT_STATUS = "N20"; // 배송준비중(=발주확인)
	private static final String CANCEL_STATUS = "C40"; // 취소완료

	@Override
	public void acceptOrder(String cafe24OrderId) {
		updateStatus(cafe24OrderId, ACCEPT_STATUS);
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

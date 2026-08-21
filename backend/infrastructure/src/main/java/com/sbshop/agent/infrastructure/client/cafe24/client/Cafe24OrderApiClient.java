package com.sbshop.agent.infrastructure.client.cafe24.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24OrderApiClient implements Cafe24OrderApiPort {

	private final Cafe24RestClient restClient;
	private final ObjectMapper objectMapper;

	private static final String ACCEPT_PROCESS_STATUS = "prepare";
	private static final String CANCEL_STATUS = "C40";

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
	public JsonNode fetchShipments(String orderId) {
		String response = restClient.get("/admin/orders/" + orderId + "/shipments");
		try {
			return objectMapper.readTree(response).path("shipments");
		} catch (Exception e) {
			throw new RuntimeException("Cafe24 배송건 파싱 실패", e);
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
	public void acceptOrder(String cafe24OrderId) {
		Map<String, Object> request = Map.of(
			"order_id", cafe24OrderId,
			"process_status", ACCEPT_PROCESS_STATUS);
		Map<String, Object> body = Map.of(
			"shop_no", 1,
			"requests", List.of(request));
		restClient.put("/admin/orders", body);
		log.info("[Cafe24] 발주확인(배송준비중): orderId={}, process_status={}", cafe24OrderId, ACCEPT_PROCESS_STATUS);
	}

	@Override
	public void cancelOrder(String cafe24OrderId) {
		updateStatus(cafe24OrderId, CANCEL_STATUS);
	}

	@Override
	public String registerShipment(String orderId, Object requestBody) {
		return restClient.post("/admin/orders/" + orderId + "/shipments", requestBody);
	}

	@Override
	public void updateShipment(String orderId, String shippingCode, Object requestBody) {
		restClient.put("/admin/orders/" + orderId + "/shipments/" + shippingCode, requestBody);
		log.info("[Cafe24 송장] 배송건 수정: orderId={}, shippingCode={}", orderId, shippingCode);
	}

	private String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private void updateStatus(String cafe24OrderId, String status) {
		Map<String, Object> body = Map.of(
			"shop_no", 1,
			"request", Map.of("status", status));
		restClient.put("/admin/orders/" + cafe24OrderId, body);
		log.info("[Cafe24] 주문상태 변경: orderId={}, status={}", cafe24OrderId, status);
	}
}

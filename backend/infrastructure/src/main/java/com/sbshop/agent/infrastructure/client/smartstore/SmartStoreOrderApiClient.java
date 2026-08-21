package com.sbshop.agent.infrastructure.client.smartstore;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartStoreOrderApiClient implements SmartStoreOrderApiPort {

	private final RestClient restClient = RestClient.create();
	private final ObjectMapper objectMapper;
	private static final String API_DOMAIN = "https://api.commerce.naver.com";

	@Override
	public JsonNode fetchOrders(String clientId, String secretKey, String fromDate, String toDate) {
		String accessToken = getAccessToken(clientId, secretKey);
		if (accessToken == null) {
			throw new RuntimeException("스마트스토어 엑세스 토큰 획득 실패. 클라이언트 ID/시크릿 키를 확인하세요.");
		}

		try {
			URI statusUri = UriComponentsBuilder.fromHttpUrl(API_DOMAIN)
				.path("/external/v1/pay-order/seller/product-orders/last-changed-statuses")
				.queryParam("lastChangedFrom", fromDate)
				.queryParam("lastChangedTo", toDate)
				.build()
				.encode()
				.toUri();

			String statusResponse = restClient.get()
				.uri(statusUri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);

			JsonNode statusNode = objectMapper.readTree(statusResponse);
			JsonNode statuses = statusNode.path("data").path("lastChangeStatuses");

			if (statuses.isMissingNode() || statuses.size() == 0) {
				return objectMapper.createArrayNode();
			}

			StringBuilder jsonBodyBuilder = new StringBuilder("{\"productOrderIds\":[");
			for (int i = 0; i < statuses.size(); i++) {
				if (i > 0)
					jsonBodyBuilder.append(",");
				jsonBodyBuilder.append("\"").append(statuses.get(i).path("productOrderId").asText()).append("\"");
			}
			jsonBodyBuilder.append("]}");

			String detailUrl = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/query";
			String detailResponse = restClient.post()
				.uri(URI.create(detailUrl))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBodyBuilder.toString())
				.retrieve()
				.body(String.class);

			JsonNode detailNode = objectMapper.readTree(detailResponse);
			return detailNode.path("data");

		} catch (RestClientResponseException re) {
			log.error("스마트스토어 주문 내역 조회 실패 (HTTP 상태: {}, 바디: {})", re.getStatusCode(), re.getResponseBodyAsString(), re);
			throw new RuntimeException("스마트스토어 주문 조회 HTTP 오류: " + re.getStatusCode(), re);
		} catch (Exception e) {
			log.error("스마트스토어 주문 내역 조회 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 주문 조회 실패: " + e.getMessage(), e);
		}
	}

	@Override
	public void shipOrder(String clientId, String secretKey, String productOrderId, String trackingNo,
		String deliveryCompanyCode) {
		String accessToken = getAccessToken(clientId, secretKey);
		if (accessToken == null) {
			throw new RuntimeException("스마트스토어 발송 처리용 엑세스 토큰 획득 실패.");
		}

		try {
			String url = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/dispatch";

			String dispatchDate = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS+09:00"));
			String payload = String.format(
				"{\"dispatchProductOrders\":[{\"productOrderId\":\"%s\",\"deliveryMethod\":\"DELIVERY\",\"deliveryCompanyCode\":\"%s\",\"trackingNumber\":\"%s\",\"dispatchDate\":\"%s\"}]}",
				productOrderId, deliveryCompanyCode, trackingNo, dispatchDate);

			log.info("스마트스토어 발송 API 요청 payload: {}", payload);

			String response = restClient.post()
				.uri(URI.create(url))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.body(String.class);

			log.info("스마트스토어 발송 API 응답: {}", response);

			JsonNode rootNode = objectMapper.readTree(response);
			SmartStoreDispatchResult.verifyAccepted(rootNode, productOrderId);

		} catch (Exception e) {
			log.error("스마트스토어 주문 발송 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 주문 발송(shipOrder) 실패: " + e.getMessage(), e);
		}
	}

	@Override
	public void confirmOrders(String clientId, String secretKey, List<String> productOrderIds) {
		String accessToken = getAccessToken(clientId, secretKey);
		if (accessToken == null) {
			throw new RuntimeException("스마트스토어 발주 확인용 엑세스 토큰 획득 실패.");
		}

		try {
			String url = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/confirm";

			String jsonBody = buildProductOrderIdsBody(productOrderIds);

			String response = restClient.post()
				.uri(URI.create(url))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBody)
				.retrieve()
				.body(String.class);

			JsonNode rootNode = objectMapper.readTree(response);
			JsonNode detail = rootNode.path("detail");
			if (detail.isArray()) {
				for (JsonNode item : detail) {
					if (!item.path("confirm").asBoolean()) {
						log.warn("스마트스토어 발주확인 실패 건: productOrderId={}, response={}",
							item.path("productOrderId").asText(), rootNode.toPrettyString());
					}
				}
			}
		} catch (Exception e) {
			log.error("스마트스토어 발주 확인 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 발주 확인(confirmOrders) 실패", e);
		}
	}

	@Override
	public void cancelOrders(String clientId, String secretKey, List<String> productOrderIds) {
		String accessToken = getAccessToken(clientId, secretKey);
		if (accessToken == null) {
			throw new RuntimeException("스마트스토어 주문 취소용 엑세스 토큰 획득 실패.");
		}

		try {
			String url = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/cancel";

			String jsonBody = buildProductOrderIdsBody(productOrderIds);

			String response = restClient.post()
				.uri(URI.create(url))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBody)
				.retrieve()
				.body(String.class);

			JsonNode rootNode = objectMapper.readTree(response);
			JsonNode detail = rootNode.path("detail");
			if (detail.isArray()) {
				for (JsonNode item : detail) {
					if (!item.path("cancel").asBoolean()) {
						log.warn("스마트스토어 주문취소 실패 건: productOrderId={}, response={}",
							item.path("productOrderId").asText(), rootNode.toPrettyString());
					}
				}
			}
		} catch (Exception e) {
			log.error("스마트스토어 주문 취소 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 주문 취소(cancelOrders) 실패", e);
		}
	}

	private String buildProductOrderIdsBody(List<String> productOrderIds) {
		StringBuilder jsonBody = new StringBuilder("{\"productOrderIds\":[");
		for (int i = 0; i < productOrderIds.size(); i++) {
			if (i > 0)
				jsonBody.append(",");
			jsonBody.append("\"").append(productOrderIds.get(i)).append("\"");
		}
		jsonBody.append("]}");
		return jsonBody.toString();
	}

	private String getAccessToken(String clientId, String secretKey) {
		try {
			long timestamp = System.currentTimeMillis();
			String pwd = clientId + "_" + timestamp;
			String hashed = BCrypt.hashpw(pwd, secretKey);
			String signature = Base64.getEncoder().encodeToString(hashed.getBytes(StandardCharsets.UTF_8));

			MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
			tokenBody.add("client_id", clientId);
			tokenBody.add("timestamp", String.valueOf(timestamp));
			tokenBody.add("client_secret_sign", signature);
			tokenBody.add("grant_type", "client_credentials");
			tokenBody.add("type", "SELF");

			String tokenResponse = restClient.post()
				.uri("https://api.commerce.naver.com/external/v1/oauth2/token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(tokenBody)
				.retrieve()
				.body(String.class);

			JsonNode tokenNode = objectMapper.readTree(tokenResponse);
			return tokenNode.path("access_token").asText();
		} catch (Exception e) {
			log.error("스마트스토어 엑세스 토큰 획득 실패", e);
			return null;
		}
	}
}

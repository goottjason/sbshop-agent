package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangOrderApiClient implements CoupangOrderApiPort {
	private final RestClient restClient = RestClient.create();
	private final ObjectMapper objectMapper;
	private static final String DOMAIN = "https://api-gateway.coupang.com";

	public JsonNode fetchOrders(String vendorId, String accessKey, String secretKey, String fromDate, String toDate,
		String status) {
		List<JsonNode> allOrders = new ArrayList<>();
		String nextToken = "";

		while (true) {
			String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/ordersheets"
				+ "?createdAtFrom=" + fromDate
				+ "&createdAtTo=" + toDate
				+ "&maxPerPage=50"
				+ "&searchType=timeframe"
				+ "&status=" + status;

			if (!nextToken.isEmpty()) {
				path += "&nextToken=" + nextToken;
			}

			String authorization = generateHmacSignature("GET", path, accessKey, secretKey);

			try {
				String response = restClient.get()
					.uri(DOMAIN + path)
					.header(HttpHeaders.AUTHORIZATION, authorization)
					.header("X-Requested-By", vendorId)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);

				JsonNode rootNode = objectMapper.readTree(response);
				if ("SUCCESS".equals(rootNode.path("code").asText()) || "200".equals(rootNode.path("code").asText())) {
					JsonNode dataNode = rootNode.path("data");

					if (dataNode.isArray()) {
						for (JsonNode order : dataNode) {
							allOrders.add(order);
						}
					}

					nextToken = rootNode.path("nextToken").asText("");
					if (nextToken.isEmpty()) {
						break;
					}
					// Rate limit protection
					Thread.sleep(300);
				} else {
					log.error("Coupang API error: {}", rootNode.path("message").asText());
					break;
				}
			} catch (org.springframework.web.client.RestClientResponseException e) {
				log.error("Coupang API HTTP Error: {} (Status: {}) - Possible Rate Limit or IP block",
					e.getStatusCode(), status);
				break;
			} catch (Exception e) {
				log.error("Failed to fetch Coupang orders: {}", e.getMessage());
				break;
			}
		}

		return objectMapper.valueToTree(allOrders);
	}

	private String generateHmacSignature(String method, String url, String accessKey, String secretKey) {
		String path = url;
		String query = "";

		if (url.contains("?")) {
			String[] parts = url.split("\\?", 2);
			path = parts[0];
			query = parts[1];
		}

		String datetime = ZonedDateTime.now(ZoneId.of("UTC"))
			.format(DateTimeFormatter.ofPattern("yyMMdd'T'HHmmss'Z'"));

		String message = datetime + method + path + query;

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec secretKeySpec = new SecretKeySpec(
				secretKey.getBytes(StandardCharsets.UTF_8),
				"HmacSHA256");
			mac.init(secretKeySpec);

			byte[] signatureBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
			String signature = java.util.HexFormat.of().formatHex(signatureBytes);

			return String.format("CEA algorithm=HmacSHA256, access-key=%s, signed-date=%s, signature=%s",
				accessKey, datetime, signature);

		} catch (Exception e) {
			throw new RuntimeException("Coupang signature generation failed", e);
		}
	}

	@Override
	public void shipOrder(String vendorId, String accessKey, String secretKey, String marketOrderNo,
		String vendorItemId, String trackingNo, String deliveryCompanyCode) {
		String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/ordersheets/" + marketOrderNo
			+ "/invoices";
		String authorization = generateHmacSignature("POST", path, accessKey, secretKey);

		String payload = String.format(
			"{\"vendorId\":\"%s\",\"orderSheetInvoiceApplyDtos\":[{\"vendorItemId\":\"%s\",\"deliveryCompanyCode\":\"%s\",\"trackingNumber\":\"%s\",\"splitShipping\":false}]}",
			vendorId, vendorItemId, deliveryCompanyCode, trackingNo);

		try {
			String response = restClient.post()
				.uri(DOMAIN + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", vendorId)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.body(String.class);

			JsonNode rootNode = objectMapper.readTree(response);
			if (!("SUCCESS".equals(rootNode.path("code").asText()) || "200".equals(rootNode.path("code").asText()))) {
				throw new RuntimeException(
					"Failed to ship order via Coupang API: " + rootNode.path("message").asText());
			}
		} catch (Exception e) {
			log.error("Error shipping order {}: {}", marketOrderNo, e.getMessage());
			throw new RuntimeException("Coupang shipOrder failed", e);
		}
	}

	@Override
	public JsonNode querySalesDetails(String vendorId, String accessKey, String secretKey,
		String recognitionDateFrom, String recognitionDateTo) {
		List<JsonNode> allItems = new ArrayList<>();
		String nextToken = "";

		while (true) {
			String path = "/v2/providers/openapi/apis/api/v1/revenue-history"
				+ "?vendorId=" + vendorId
				+ "&recognitionDateFrom=" + recognitionDateFrom
				+ "&recognitionDateTo=" + recognitionDateTo
				+ "&token=" + nextToken
				+ "&maxPerPage=50";

			String authorization = generateHmacSignature("GET", path, accessKey, secretKey);

			try {
				String response = restClient.get()
					.uri(DOMAIN + path)
					.header(HttpHeaders.AUTHORIZATION, authorization)
					.header("X-Requested-By", vendorId)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);

				JsonNode rootNode = objectMapper.readTree(response);
				if ("SUCCESS".equals(rootNode.path("code").asText()) || "200".equals(rootNode.path("code").asText())) {
					JsonNode dataNode = rootNode.path("data");

					if (dataNode.isArray()) {
						for (JsonNode item : dataNode) {
							allItems.add(item);
						}
					}

					nextToken = rootNode.path("nextToken").asText("");
					if (nextToken.isEmpty()) {
						break;
					}
					Thread.sleep(300);
				} else {
					log.error("Coupang Sales Detail API error: {}", rootNode.path("message").asText());
					break;
				}
			} catch (org.springframework.web.client.RestClientResponseException e) {
				log.error("Coupang Sales Detail API HTTP Error: {}", e.getStatusCode());
				break;
			} catch (Exception e) {
				log.error("Failed to query Coupang sales details: {}", e.getMessage());
				break;
			}
		}

		return objectMapper.valueToTree(allItems);
	}

	@Override
	public JsonNode queryProduct(String vendorId, String accessKey, String secretKey, long sellerProductId) {
		String path = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/" + sellerProductId;
		String authorization = generateHmacSignature("GET", path, accessKey, secretKey);

		try {
			String response = restClient.get()
				.uri(DOMAIN + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", vendorId)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);

			JsonNode rootNode = objectMapper.readTree(response);
			if ("SUCCESS".equals(rootNode.path("code").asText()) || "200".equals(rootNode.path("code").asText())) {
				return rootNode.path("data");
			} else {
				log.error("Coupang Product API error: {}", rootNode.path("message").asText());
				return null;
			}
		} catch (Exception e) {
			log.error("Failed to query Coupang product (sellerProductId={}): {}", sellerProductId, e.getMessage());
			return null;
		}
	}

	@Override
	public void acceptOrders(String vendorId, String accessKey, String secretKey,
		java.util.List<String> shipmentBoxIds) {
		String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/ordersheets/acknowledgement";
		String authorization = generateHmacSignature("PUT", path, accessKey, secretKey);

		StringBuilder jsonBody = new StringBuilder("{\"vendorId\":\"").append(vendorId)
			.append("\",\"shipmentBoxIds\":[");
		for (int i = 0; i < shipmentBoxIds.size(); i++) {
			if (i > 0)
				jsonBody.append(",");
			jsonBody.append("\"").append(shipmentBoxIds.get(i)).append("\"");
		}
		jsonBody.append("]}");

		try {
			String response = restClient.put()
				.uri(DOMAIN + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", vendorId)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBody.toString())
				.retrieve()
				.body(String.class);

			JsonNode rootNode = objectMapper.readTree(response);
			if (!("SUCCESS".equals(rootNode.path("code").asText()) || "200".equals(rootNode.path("code").asText()))) {
				throw new RuntimeException(
					"Failed to accept orders via Coupang API: " + rootNode.path("message").asText());
			}
		} catch (Exception e) {
			log.error("Error accepting Coupang orders (shipmentBoxIds: {}): {}", shipmentBoxIds, e.getMessage());
			throw new RuntimeException("Coupang acceptOrders failed", e);
		}
	}
}

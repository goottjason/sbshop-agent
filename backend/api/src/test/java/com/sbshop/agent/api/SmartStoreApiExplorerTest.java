package com.sbshop.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SmartStoreApiExplorerTest {

	private final String clientId = "1l5fRuKFzyNJGQF3AP27AE";
	private final String clientSecret = "$2a$04$24Vxb0j6X3HK.ZVUA43Wk.";
	private final RestTemplate restTemplate = new RestTemplateBuilder().build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	public void exploreSmartStoreApi() throws Exception {
		System.out.println("=== 1. Generating SmartStore Signature ===");
		long timestamp = System.currentTimeMillis();
		String pwd = clientId + "_" + timestamp;
		String hashed = BCrypt.hashpw(pwd, clientSecret);
		String signature = Base64.getEncoder().encodeToString(hashed.getBytes(StandardCharsets.UTF_8));

		System.out.println("Timestamp: " + timestamp);
		System.out.println("Signature: " + signature);

		System.out.println("\n=== 2. Requesting Access Token ===");
		HttpHeaders tokenHeaders = new HttpHeaders();
		tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
		tokenBody.add("client_id", clientId);
		tokenBody.add("timestamp", String.valueOf(timestamp));
		tokenBody.add("client_secret_sign", signature);
		tokenBody.add("grant_type", "client_credentials");
		tokenBody.add("type", "SELF");

		HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(tokenBody, tokenHeaders);
		ResponseEntity<String> tokenResponse = restTemplate.postForEntity(
			"https://api.commerce.naver.com/external/v1/oauth2/token",
			tokenRequest,
			String.class);

		if (!tokenResponse.getStatusCode().is2xxSuccessful()) {
			System.err.println("Token request failed: " + tokenResponse.getBody());
			return;
		}

		JsonNode tokenNode = objectMapper.readTree(tokenResponse.getBody());
		String accessToken = tokenNode.get("access_token").asText();
		System.out.println("Access Token Acquired: " + accessToken.substring(0, 20) + "...");

		System.out.println("\n=== 3. Fetching Last Changed Product Orders ===");
		String fromDate = "2026-06-01T00:00:00.000%2B09:00";
		String urlStr = "https://api.commerce.naver.com/external/v1/pay-order/seller/product-orders/last-changed-statuses?lastChangedFrom="
			+ fromDate;

		java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(java.net.URI.create(urlStr))
			.header("Authorization", "Bearer " + accessToken)
			.GET()
			.build();

		java.net.http.HttpResponse<String> response = client.send(request,
			java.net.http.HttpResponse.BodyHandlers.ofString());

		System.out.println("Orders Response Status: " + response.statusCode());
		JsonNode ordersNode = objectMapper.readTree(response.body());
		System.out.println("Orders Response Data:\n" + ordersNode.toPrettyString());

		if (response.statusCode() != 200) {
			System.err.println("API Request Failed with status: " + response.statusCode());
			System.err.println("Error Body: " + response.body());
			return;
		}

		// Step 4: If there are productOrderIds, fetch details for all of them
		if (ordersNode.has("data") && ordersNode.get("data").has("lastChangeStatuses")
			&& ordersNode.get("data").get("lastChangeStatuses").size() > 0) {
			JsonNode statuses = ordersNode.get("data").get("lastChangeStatuses");
			StringBuilder jsonBodyBuilder = new StringBuilder("[");
			for (int i = 0; i < statuses.size(); i++) {
				if (i > 0)
					jsonBodyBuilder.append(",");
				jsonBodyBuilder.append("\"").append(statuses.get(i).get("productOrderId").asText()).append("\"");
			}
			jsonBodyBuilder.append("]");

			String jsonBody = "{\"productOrderIds\":" + jsonBodyBuilder.toString() + "}";
			System.out.println("\n=== 4. Fetching Order Details for ProductOrderIds: " + jsonBody + " ===");

			java.net.http.HttpRequest detailReq = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI
					.create("https://api.commerce.naver.com/external/v1/pay-order/seller/product-orders/query"))
				.header("Authorization", "Bearer " + accessToken)
				.header("Content-Type", "application/json")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();

			java.net.http.HttpResponse<String> detailRes = client.send(detailReq,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			System.out.println("Order Detail Response Status: " + detailRes.statusCode());
			System.out.println("Order Detail Data:\n" + objectMapper.readTree(detailRes.body()).toPrettyString());
		} else {
			System.out.println("\nNo recently changed orders found to fetch details for.");
		}
	}
}

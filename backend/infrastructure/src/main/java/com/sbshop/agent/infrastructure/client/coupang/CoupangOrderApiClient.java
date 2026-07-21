package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.sbshop.agent.core.application.order.port.CoupangCancelOrderRequest;
import com.sbshop.agent.core.application.order.port.CoupangInvoiceUploadRequest;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.port.CoupangUpdateInvoiceRequest;
import com.sbshop.agent.core.domain.market.MarketCredential;
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

	public JsonNode fetchOrders(MarketCredential credential, String fromDate, String toDate, String status) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

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
					log.error("쿠팡 API 오류: {}", rootNode.path("message").asText());
					break;
				}
			} catch (org.springframework.web.client.RestClientResponseException e) {
				// D-041: HTTP 오류(403 등)를 삼켜 "성공 0건"으로 위장하지 않고 상태코드를 담아 전파한다.
				// 어댑터가 전량 실패를 감지해 SYNC_FAILED로 이어지게 한다.
				log.error("쿠팡 API HTTP 오류: {} (상태: {}) - 속도 제한 또는 IP 차단 가능성",
					e.getStatusCode(), status);
				throw new RuntimeException(
					"쿠팡 API HTTP 오류: " + e.getStatusCode() + " (status=" + status + ")", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("쿠팡 주문 조회 중단됨 (status=" + status + ")", e);
			} catch (Exception e) {
				log.error("쿠팡 주문 조회 실패: {}", e.getMessage());
				throw new RuntimeException(
					"쿠팡 주문 조회 실패: " + e.getMessage() + " (status=" + status + ")", e);
			}
		}

		return objectMapper.valueToTree(allOrders);
	}

	private String generateHmacSignature(String method, String url, String accessKey, String secretKey) {
		return CoupangHmacUtil.generateSignatureUtc(method, url, accessKey, secretKey);
	}

	/**
	 * 쿠팡 반품요청 목록 조회 (v4 returnRequests, searchType=timeFrame). D-097.
	 * createdAt 기준 [from, to] 구간을 조회한다. 구간 폭 제한(≈7일)을 넘지 않도록 7일 창으로 분할하고,
	 * nextToken으로 페이지를 순회한다. status는 미지정(전 상태) — 어댑터가 RETURNS_COMPLETED만 필터링한다.
	 */
	@Override
	public JsonNode queryReturns(MarketCredential credential, String fromDate, String toDate) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

		List<JsonNode> all = new ArrayList<>();
		java.time.LocalDate from = java.time.LocalDate.parse(fromDate);
		java.time.LocalDate to = java.time.LocalDate.parse(toDate);

		for (java.time.LocalDate windowStart = from; !windowStart.isAfter(to); windowStart = windowStart.plusDays(7)) {
			java.time.LocalDate windowEnd = windowStart.plusDays(6);
			if (windowEnd.isAfter(to)) {
				windowEnd = to;
			}
			String createdAtFrom = windowStart + "T00:00";
			String createdAtTo = windowEnd + "T23:59";
			String nextToken = "";

			while (true) {
				String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/returnRequests"
					+ "?searchType=timeFrame"
					+ "&createdAtFrom=" + createdAtFrom
					+ "&createdAtTo=" + createdAtTo
					+ "&maxPerPage=50";
				if (!nextToken.isEmpty()) {
					path += "&nextToken=" + nextToken;
				}

				String authorization = generateHmacSignature("GET", path, accessKey, secretKey);

				try {
					// URI.create로 그대로 전송(템플릿 인코딩 회피) — createdAt의 콜론이 서명 대상 쿼리와 일치해야 함.
					String response = restClient.get()
						.uri(java.net.URI.create(DOMAIN + path))
						.header(HttpHeaders.AUTHORIZATION, authorization)
						.header("X-Requested-By", vendorId)
						.accept(MediaType.APPLICATION_JSON)
						.retrieve()
						.body(String.class);

					JsonNode root = objectMapper.readTree(response);
					String code = root.path("code").asText();
					if (!"200".equals(code) && !"SUCCESS".equalsIgnoreCase(code)) {
						log.error("쿠팡 반품조회 오류: {} ({}~{})",
							root.path("message").asText(), createdAtFrom, createdAtTo);
						break;
					}

					JsonNode data = root.path("data");
					if (data.isArray()) {
						for (JsonNode n : data) {
							all.add(n);
						}
					}

					nextToken = root.path("nextToken").asText("");
					if (nextToken.isEmpty()) {
						break;
					}
					Thread.sleep(300);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("쿠팡 반품조회 중단됨", e);
				} catch (Exception e) {
					log.error("쿠팡 반품조회 실패: {} ({}~{})", e.getMessage(), createdAtFrom, createdAtTo);
					break;
				}
			}
		}

		return objectMapper.valueToTree(all);
	}

	@Override
	public void shipOrder(MarketCredential credential, CoupangInvoiceUploadRequest request) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

		String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/orders/invoices";
		String authorization = generateHmacSignature("POST", path, accessKey, secretKey);

		try {
			String payload = objectMapper.writeValueAsString(request);

			String response = restClient.post()
				.uri(DOMAIN + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", vendorId)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.body(String.class);

			CoupangInvoiceResponse invoiceResponse = objectMapper.readValue(response, CoupangInvoiceResponse.class);
			if (!invoiceResponse.isSuccessful()) {
				throw new RuntimeException(
					"쿠팡 송장업로드 실패: " + invoiceResponse.failureReason());
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			log.error("쿠팡 송장업로드 실패: {}", e.getMessage());
			throw new RuntimeException("Coupang shipOrder failed", e);
		}
	}

	@Override
	public void updateTracking(MarketCredential credential, CoupangUpdateInvoiceRequest request) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

		String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/orders/updateInvoices";
		String authorization = generateHmacSignature("POST", path, accessKey, secretKey);

		try {
			String payload = objectMapper.writeValueAsString(request);

			String response = restClient.post()
				.uri(DOMAIN + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", vendorId)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.body(String.class);

			CoupangInvoiceResponse invoiceResponse = objectMapper.readValue(response, CoupangInvoiceResponse.class);
			if (!invoiceResponse.isSuccessful()) {
				throw new RuntimeException(
					"쿠팡 송장업데이트 실패: " + invoiceResponse.failureReason());
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			log.error("쿠팡 송장업데이트 실패: {}", e.getMessage());
			throw new RuntimeException("Coupang updateTracking failed", e);
		}
	}

	@Override
	public JsonNode querySalesDetails(MarketCredential credential,
		String recognitionDateFrom, String recognitionDateTo) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

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
					log.error("쿠팡 매출상세 API 오류: {}", rootNode.path("message").asText());
					break;
				}
			} catch (org.springframework.web.client.RestClientResponseException e) {
				log.error("쿠팡 매출상세 API HTTP 오류: {}", e.getStatusCode());
				break;
			} catch (Exception e) {
				log.error("쿠팡 매출상세 조회 실패: {}", e.getMessage());
				break;
			}
		}

		return objectMapper.valueToTree(allItems);
	}

	@Override
	public JsonNode queryProduct(MarketCredential credential, long sellerProductId) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

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
				log.error("쿠팡 상품 API 오류: {}", rootNode.path("message").asText());
				return null;
			}
		} catch (Exception e) {
			log.error("쿠팡 상품 조회 실패 (sellerProductId={}): {}", sellerProductId, e.getMessage());
			return null;
		}
	}

	@Override
	public void acceptOrders(MarketCredential credential, List<String> shipmentBoxIds) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

		String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendorId + "/ordersheets/acknowledgement";
		String authorization = generateHmacSignature("PUT", path, accessKey, secretKey);

		try {
			var request = new CoupangAcceptOrdersRequest(vendorId, shipmentBoxIds);
			String payload = objectMapper.writeValueAsString(request);

			String response = restClient.put()
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
					"Failed to accept orders via Coupang API: " + rootNode.path("message").asText());
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			log.error("쿠팡 주문 접수 실패 (shipmentBoxIds: {}): {}", shipmentBoxIds, e.getMessage());
			throw new RuntimeException("Coupang acceptOrders failed", e);
		}
	}

	@Override
	public void cancelOrder(MarketCredential credential, CoupangCancelOrderRequest request) {
		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

		String path = "/v2/providers/openapi/apis/api/v5/vendors/" + vendorId
			+ "/orders/" + request.orderId() + "/cancel";
		String authorization = generateHmacSignature("POST", path, accessKey, secretKey);

		try {
			String payload = objectMapper.writeValueAsString(request);

			String response = restClient.post()
				.uri(DOMAIN + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", vendorId)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.body(String.class);

			CoupangCancelOrderResponse cancelResponse = objectMapper.readValue(response,
				CoupangCancelOrderResponse.class);
			if (!cancelResponse.isSuccessful()) {
				throw new RuntimeException(
					"쿠팡 주문취소 실패: " + cancelResponse.message());
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			log.error("쿠팡 주문취소 실패 (orderId: {}): {}", request.orderId(), e.getMessage());
			throw new RuntimeException("Coupang cancelOrder failed", e);
		}
	}
}

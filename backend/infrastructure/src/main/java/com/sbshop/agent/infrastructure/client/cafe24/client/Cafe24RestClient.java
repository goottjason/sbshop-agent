package com.sbshop.agent.infrastructure.client.cafe24.client;

import com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24RestClient {

	private final Cafe24TokenManager tokenManager;
	private final RestClient restClient = boundedRestClient();

	public String accountReference() {
		return com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.account("CAFE24",
			tokenManager.getApiUrl());
	}

	private static RestClient boundedRestClient() {
		var http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
			.connectTimeout(java.time.Duration.ofSeconds(10)).build();
		var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(java.time.Duration.ofSeconds(30));
		return com.sbshop.agent.infrastructure.client.common.MarketPreparationTransport
			.guarded(RestClient.builder().requestFactory(factory),
				com.sbshop.agent.core.domain.order.enums.MarketType.CAFE24)
			.build();
	}

	public String get(String path) {
		try {
			return restClient.get()
				.uri(getBaseUrl() + freshProductRead(path))
				.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenManager.getValidAccessToken())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);
		} catch (Exception e) {
			log.error("[Cafe24 GET Error] path: {}, msg: {}", path, e.getMessage());
			throw new RuntimeException(enrich("Cafe24 API 호출 실패", e), e);
		}
	}

	// Cafe24 can return X-Cache:HIT with pre-write values even with no-cache headers.
	// A unique query token was verified against the live Admin product API (2026-09-09).
	// This is a cache-key discriminator, not a Cafe24 product filter or a success assertion.
	private String freshProductRead(String path) {
		if (path.equals("/admin/products") || path.startsWith("/admin/products/")
			|| path.startsWith("/admin/products?"))
			return path + (path.contains("?") ? "&" : "?") + "_sb_read=" + java.util.UUID.randomUUID();
		return path;
	}

	public String post(String path, Object body) {
		try {
			return restClient.post()
				.uri(getBaseUrl() + path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenManager.getValidAccessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(String.class);
		} catch (Exception e) {
			log.error("[Cafe24 POST Error] path: {}, msg: {}", path, e.getMessage());
			throw new RuntimeException(enrich("Cafe24 API POST 호출 실패", e), e);
		}
	}

	public String put(String path, Object body) {
		try {
			return restClient.put()
				.uri(getBaseUrl() + path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenManager.getValidAccessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(String.class);
		} catch (Exception e) {
			log.error("[Cafe24 PUT Error] path: {}, msg: {}", path, e.getMessage());
			throw new RuntimeException(enrich("Cafe24 API PUT 호출 실패", e), e);
		}
	}

	public void delete(String path) {
		try {
			restClient.delete()
				.uri(getBaseUrl() + path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenManager.getValidAccessToken())
				.retrieve()
				.toBodilessEntity();
		} catch (Exception e) {
			log.error("[Cafe24 DELETE Error] path: {}, msg: {}", path, e.getMessage());
			throw new RuntimeException(enrich("Cafe24 API DELETE 호출 실패", e), e);
		}
	}

	public byte[] getExternalImageBytes(String url) {
		try {
			return restClient.get()
				.uri(url)
				.retrieve()
				.body(byte[].class);
		} catch (Exception e) {
			log.error("외부 이미지 다운로드 실패: {}, msg: {}", url, e.getMessage());
			return null;
		}
	}

	private String getBaseUrl() {
		String apiUrl = tokenManager.getApiUrl();
		return apiUrl != null ? apiUrl : "";
	}

	private String enrich(String prefix, Exception e) {
		if (e instanceof HttpStatusCodeException httpEx) {
			String body = httpEx.getResponseBodyAsString();
			String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
			return prefix + "(" + httpEx.getStatusCode().value() + "): " + snippet;
		}
		String msg = e.getMessage();
		return msg == null ? prefix : prefix + ": " + msg;
	}
}

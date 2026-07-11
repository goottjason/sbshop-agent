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
	private final RestClient restClient = RestClient.create();

	private String getBaseUrl() {
		String apiUrl = tokenManager.getApiUrl();
		return apiUrl != null ? apiUrl : "";
	}

	public String get(String path) {
		try {
			return restClient.get()
				.uri(getBaseUrl() + path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenManager.getValidAccessToken())
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);
		} catch (Exception e) {
			log.error("[Cafe24 GET Error] path: {}, msg: {}", path, e.getMessage());
			throw new RuntimeException(enrich("Cafe24 API 호출 실패", e), e);
		}
	}

	/**
	 * 원 예외의 의미를 메시지에 포함해 root cause 은폐를 방지한다.
	 * HTTP 오류면 상태코드와 응답 본문 앞부분을, 그 외 예외는 원 메시지를 붙인다.
	 * 토큰 등 시크릿은 본문에 담기지 않는 전제이나, 만약을 대비해 길이를 제한한다.
	 */
	private String enrich(String prefix, Exception e) {
		if (e instanceof HttpStatusCodeException httpEx) {
			String body = httpEx.getResponseBodyAsString();
			String snippet = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
			return prefix + "(" + httpEx.getStatusCode().value() + "): " + snippet;
		}
		String msg = e.getMessage();
		return msg == null ? prefix : prefix + ": " + msg;
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
			throw new RuntimeException("Cafe24 API PUT 호출 실패", e);
		}
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
			throw new RuntimeException("Cafe24 API POST 호출 실패", e);
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
			throw new RuntimeException("Cafe24 API DELETE 호출 실패", e);
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
}

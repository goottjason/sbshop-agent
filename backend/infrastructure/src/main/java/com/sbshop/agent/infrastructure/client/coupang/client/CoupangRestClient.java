package com.sbshop.agent.infrastructure.client.coupang.client;

import com.sbshop.agent.infrastructure.client.coupang.CoupangHmacUtil;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangRestClient {

	private final CoupangProperties properties;
	private final RestClient restClient = RestClient.create();

	public String get(String path) {
		return request("GET", path, null);
	}

	public String put(String path, Object body) {
		return request("PUT", path, body);
	}

	public String post(String path, Object body) {
		return request("POST", path, body);
	}

	public void delete(String path) {
		request("DELETE", path, null);
	}

	public String requestWithBody(String method, String path, Object body) {
		return request(method, path, body);
	}

	private String request(String method, String path, Object body) {
		try {
			String authorization = CoupangHmacUtil.generateSignature(
					method, path, properties.getAccessKey(), properties.getSecretKey());
			String datetime = CoupangHmacUtil.generateDatetime();

			var requestSpec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
					.uri(properties.getApiUrl() + path)
					.header(HttpHeaders.AUTHORIZATION, authorization)
					.header("X-Requested-By", properties.getVendorId())
					.header("signed-date", datetime);

			if (body != null) {
				requestSpec.contentType(MediaType.APPLICATION_JSON).body(body);
			}

			return requestSpec.retrieve().body(String.class);
		} catch (Exception e) {
			log.error("[Coupang {} Error] path: {}, msg: {}", method, path, e.getMessage());
			throw new RuntimeException("Coupang API 호출 실패", e);
		}
	}
}

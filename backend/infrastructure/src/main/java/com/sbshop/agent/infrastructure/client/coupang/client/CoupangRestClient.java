package com.sbshop.agent.infrastructure.client.coupang.client;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.coupang.CoupangHmacUtil;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangRestClient {

	private final CoupangProperties properties;
	private final MarketCredentialRepository marketCredentialRepository;
	private final RestClient restClient = RestClient.create();

	public String get(String path) {
		return request("GET", path, null);
	}

	public String post(String path, Object body) {
		return request("POST", path, body);
	}

	public String put(String path, Object body) {
		return request("PUT", path, body);
	}

	public void delete(String path) {
		request("DELETE", path, null);
	}

	public String requestWithBody(String method, String path, Object body) {
		return request(method, path, body);
	}

	private String request(String method, String path, Object body) {
		try {
			String[] cred = resolveCredentials();
			String authorization = CoupangHmacUtil.generateSignatureUtc(
				method, path, cred[0], cred[1]);

			var requestSpec = restClient.method(HttpMethod.valueOf(method))
				.uri(properties.getApiUrl() + path)
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.header("X-Requested-By", cred[2]);

			if (body != null) {
				requestSpec.contentType(MediaType.APPLICATION_JSON).body(body);
			}

			return requestSpec.retrieve().body(String.class);
		} catch (Exception e) {
			log.error("[Coupang {} Error] path: {}, msg: {}", method, path, e.getMessage());
			throw new RuntimeException("Coupang API 호출 실패", e);
		}
	}

	private String[] resolveCredentials() {
		MarketCredential db = marketCredentialRepository.findByMarketType(MarketType.COUPANG).orElse(null);
		String accessKey = (db != null && !blank(db.getAccessKey())) ? db.getAccessKey() : properties.getAccessKey();
		String secretKey = (db != null && !blank(db.getSecretKey())) ? db.getSecretKey() : properties.getSecretKey();
		String vendorId = (db != null && !blank(db.getClientId())) ? db.getClientId() : properties.getVendorId();
		if (blank(accessKey) || blank(secretKey)) {
			throw new IllegalStateException("쿠팡 API 자격증명 미설정 (DB sb_market_credential·env COUPANG_* 모두 없음)");
		}
		return new String[] {accessKey, secretKey, vendorId};
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}
}

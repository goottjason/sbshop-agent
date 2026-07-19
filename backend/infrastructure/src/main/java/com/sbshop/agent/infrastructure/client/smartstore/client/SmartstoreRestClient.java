package com.sbshop.agent.infrastructure.client.smartstore.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.smartstore.config.SmartstoreProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreRestClient {

	private final SmartstoreProperties properties;
	private final ObjectMapper objectMapper;
	// 자격증명 단일 소스: DB(sb_market_credential SMART_STORE) 우선, 없으면 env(SmartstoreProperties) 폴백.
	private final MarketCredentialRepository marketCredentialRepository;
	private final RestClient restClient = RestClient.create();

	private String accessToken;
	// 토큰 만료 시각. Naver 커머스 토큰은 ~3시간 후 만료되는데, 과거엔 accessToken이 null일 때만
	// 재발급해 만료된 토큰을 계속 재사용 → 재시작 전까지 상품 API가 401 GW.AUTHN을 반환했다.
	private volatile Instant tokenExpiresAt = Instant.EPOCH;

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private String resolveClientId() {
		MarketCredential c = marketCredentialRepository.findByMarketType(MarketType.SMART_STORE).orElse(null);
		return (c != null && !blank(c.getClientId())) ? c.getClientId() : properties.getClientId();
	}

	private String resolveClientSecret() {
		MarketCredential c = marketCredentialRepository.findByMarketType(MarketType.SMART_STORE).orElse(null);
		return (c != null && !blank(c.getSecretKey())) ? c.getSecretKey() : properties.getClientSecret();
	}

	public synchronized String getValidAccessToken() {
		// 미발급이거나 만료(버퍼 포함)면 재발급 — 만료 토큰 재사용으로 인한 401을 방지한다.
		if (accessToken == null || Instant.now().isAfter(tokenExpiresAt)) {
			fetchAccessToken();
		}
		return accessToken;
	}

	private synchronized void invalidateToken() {
		accessToken = null;
		tokenExpiresAt = Instant.EPOCH;
	}

	private void fetchAccessToken() {
		String clientId = resolveClientId();
		String timestamp = String.valueOf(Instant.now().toEpochMilli());
		String password = clientId + "_" + timestamp;
		String clientSecretSign = Base64.getEncoder()
			.encodeToString(BCrypt.hashpw(password, resolveClientSecret()).getBytes(StandardCharsets.UTF_8));

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", clientId);
		form.add("timestamp", timestamp);
		form.add("client_secret_sign", clientSecretSign);
		form.add("type", "SELF");

		try {
			String response = restClient.post()
				.uri(properties.getApiUrl() + "/v1/oauth2/token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(String.class);
			JsonNode node = objectMapper.readTree(response);
			accessToken = node.path("access_token").asText();
			// expires_in(초) - 60초 버퍼로 만료 시각 계산. 응답에 없으면 3시간 기본.
			long expiresIn = node.path("expires_in").asLong(10800);
			tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
			log.info("[Smartstore] OAuth2 토큰 발급 완료 (만료 {}s 후)", expiresIn);
		} catch (Exception e) {
			log.error("[Smartstore] 토큰 발급 실패", e);
			throw new RuntimeException("Smartstore OAuth2 인증 실패", e);
		}
	}

	public String get(String path) {
		return request("GET", path, null);
	}

	public String post(String path, Object body) {
		return request("POST", path, body);
	}

	public String put(String path, Object body) {
		return request("PUT", path, body);
	}

	public String delete(String path) {
		return request("DELETE", path, null);
	}

	private String request(String method, String path, Object body) {
		try {
			return doRequest(method, path, body);
		} catch (HttpClientErrorException e) {
			// 401(GW.AUTHN): 만료/무효 토큰일 수 있으므로 토큰을 무효화하고 1회 재발급 재시도한다.
			if (e.getStatusCode().value() == 401) {
				log.warn("[Smartstore] 401 인증 실패 — 토큰 재발급 후 1회 재시도: {} {}", method, path);
				invalidateToken();
				try {
					return doRequest(method, path, body);
				} catch (Exception retry) {
					log.error("[Smartstore {} Error(재시도 후)] path: {}, msg: {}", method, path, retry.getMessage());
					throw new RuntimeException("Smartstore API 호출 실패", retry);
				}
			}
			log.error("[Smartstore {} Error] path: {}, msg: {}", method, path, e.getMessage());
			throw new RuntimeException("Smartstore API 호출 실패", e);
		} catch (Exception e) {
			log.error("[Smartstore {} Error] path: {}, msg: {}", method, path, e.getMessage());
			throw new RuntimeException("Smartstore API 호출 실패", e);
		}
	}

	private String doRequest(String method, String path, Object body) {
		var spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
			.uri(properties.getApiUrl() + path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + getValidAccessToken())
			.header("X-Time-Stamp", String.valueOf(Instant.now().toEpochMilli()));
		if (body != null) {
			spec.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		return spec.retrieve().body(String.class);
	}
}

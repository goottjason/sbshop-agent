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
		if (accessToken == null) {
			fetchAccessToken();
		}
		return accessToken;
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
			log.info("[Smartstore] OAuth2 토큰 발급 완료");
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
			var spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
				.uri(properties.getApiUrl() + path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + getValidAccessToken())
				.header("X-Time-Stamp", String.valueOf(Instant.now().toEpochMilli()));
			if (body != null) {
				spec.contentType(MediaType.APPLICATION_JSON).body(body);
			}
			return spec.retrieve().body(String.class);
		} catch (Exception e) {
			log.error("[Smartstore {} Error] path: {}, msg: {}", method, path, e.getMessage());
			throw new RuntimeException("Smartstore API 호출 실패", e);
		}
	}
}

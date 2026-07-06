package com.sbshop.agent.infrastructure.client.cafe24;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24TokenManager {

	private final MarketCredentialRepository marketCredentialRepository;
	private final RestClient restClient = RestClient.create();

	private String accessToken;
	private Instant tokenExpiresAt;

	@PostConstruct
	public void init() {
		MarketCredential credential = getCredential();
		if (credential == null
			|| credential.getClientId() == null
			|| credential.getSecretKey() == null) {
			log.warn("🚨 Cafe24 API 정보가 등록되지 않았습니다. 설정 페이지에서 먼저 키를 입력해주세요.");
			return;
		}

		if (credential.getRefreshToken() == null || credential.getRefreshToken().isBlank()) {
			log.warn("🚨 Cafe24 인증이 필요합니다! 브라우저에서 아래 URL로 접속해 인증 코드를 받아주세요.");
			log.warn(generateAuthorizationUrl(credential));
		} else {
			refreshAccessToken(); // 서버 시작 시 기존 리프레시 토큰으로 자동 갱신
		}
	}

	private MarketCredential getCredential() {
		return marketCredentialRepository.findByMarketType(MarketType.CAFE24).orElse(null);
	}

	public synchronized String getValidAccessToken() {
		// 만료 5분 전이면 안전하게 갱신
		if (accessToken == null
			|| tokenExpiresAt == null
			|| tokenExpiresAt.minusSeconds(300).isBefore(Instant.now())) {
			refreshAccessToken();
		}
		return accessToken;
	}

	public String getApiUrl() {
		MarketCredential credential = getCredential();
		if (credential == null) return null;
		return "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
	}

	private void refreshAccessToken() {
		MarketCredential credential = getCredential();
		if (credential == null)
			return;

		String refreshToken = credential.getRefreshToken();
		if (refreshToken == null || refreshToken.isBlank())
			return;

		try {
			String payload = "grant_type=refresh_token&refresh_token=" + refreshToken;
			requestTokenToCafe24(payload, credential);

			String kstTimeStr = this.tokenExpiresAt
				.atZone(ZoneId.of("Asia/Seoul"))
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

			log.info("✅ Cafe24 토큰 갱신 완료 (만료일시: {})", kstTimeStr);

		} catch (Exception e) {
			log.error("❌ Cafe24 토큰 갱신 실패. 리프레시 토큰이 만료되었거나 잘못되었습니다.", e);
		}
	}

	private void requestTokenToCafe24(String payload, MarketCredential credential) {
		String clientId = credential.getClientId();
		String clientSecret = credential.getSecretKey();

		// Cafe24 uses accessKey mapping for Client ID in some projects, but in my MarketCredential:
		// clientId -> Cafe24 Mall ID
		// accessKey -> Cafe24 Client ID
		// secretKey -> Cafe24 Client Secret
		String actualClientId = credential.getAccessKey();

		String authHeader = "Basic "
			+ Base64.getEncoder()
				.encodeToString(
					(actualClientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

		// Endpoint format: https://{mall_id}.cafe24api.com/api/v2/oauth/token
		String apiUrl = "https://" + credential.getClientId() + ".cafe24api.com/api/v2";

		try {
			JsonNode response = restClient
				.post()
				.uri(apiUrl + "/oauth/token")
				.header(HttpHeaders.AUTHORIZATION, authHeader)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(payload)
				.retrieve()
				.onStatus(
					status -> status.is4xxClientError() || status.is5xxServerError(),
					(request, resp) -> {
						String errorBody = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
						log.error("❌ Cafe24 API 호출 에러 응답: {}", errorBody);
						throw new RuntimeException("Cafe24 API Error: " + errorBody);
					})
				.body(JsonNode.class);

			if (response != null && response.has("access_token")) {
				this.accessToken = response.get("access_token").asText();

				String expiresAtStr = response.get("expires_at").asText().replace(" ", "T");
				this.tokenExpiresAt = LocalDateTime.parse(expiresAtStr).atZone(ZoneId.of("Asia/Seoul")).toInstant();

				// 발급된 새 리프레시 토큰을 DB에 저장합니다.
				String newRefreshToken = response.get("refresh_token").asText();
				credential.setRefreshToken(newRefreshToken);
				marketCredentialRepository.save(credential);
			}
		} catch (Exception e) {
			throw new RuntimeException("Cafe24 API Request failed", e);
		}
	}

	public String generateAuthorizationUrl(MarketCredential credential) {
		String apiUrl = "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
		String scope = "mall.read_product,mall.write_product";
		return String.format(
			"%s/oauth/authorize?response_type=code&client_id=%s&state=shouldbeshopping&redirect_uri=%s&scope=%s",
			apiUrl, credential.getAccessKey(), credential.getRedirectUri(), scope);
	}

	public void issueInitialToken(String code) {
		MarketCredential credential = getCredential();
		if (credential == null) {
			throw new RuntimeException("Cafe24 credential not found in DB.");
		}
		try {
			String payload = String.format(
				"grant_type=authorization_code&code=%s&redirect_uri=%s",
				code, credential.getRedirectUri());
			requestTokenToCafe24(payload, credential);
			log.info("🎉 [최초 인증 성공] 리프레시 토큰이 성공적으로 발급되어 DB에 저장되었습니다!");
		} catch (Exception e) {
			log.error("❌ 최초 인증 코드(code)로 토큰을 발급받는 데 실패했습니다.", e);
			throw new RuntimeException("최초 토큰 발급 실패", e);
		}
	}
}

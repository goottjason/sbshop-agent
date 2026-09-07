package com.sbshop.agent.infrastructure.client.cafe24;

import com.sbshop.agent.core.application.market.port.Cafe24TokenRefreshPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.TokenRefreshLock;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24TokenManager implements Cafe24TokenRefreshPort {

	private static final long CAFE24_TOKEN_LOCK_KEY = 0xCAFE24L;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MarketCredentialRepository marketCredentialRepository;
	private final Cafe24OAuthTokenClient tokenClient;
	private final TokenRefreshLock refreshLock;

	@PostConstruct
	public void init() {
		MarketCredential c = getCredential();
		if (c == null || c.getClientId() == null || c.getSecretKey() == null) {
			log.warn("🚨 Cafe24 API 정보 미등록 — 설정 페이지에서 키를 입력하세요.");
		} else if (c.getRefreshToken() == null || c.getRefreshToken().isBlank()) {
			log.warn("🚨 Cafe24 재인증 필요 — 설정 페이지에서 저장된 계정의 인증 주소를 확인하세요.");
		} else {
			log.info("✅ Cafe24 자격증명 확인됨 — 토큰은 최초 사용 시 필요하면 갱신합니다.");
		}
	}

	public boolean isRefreshTokenPresent() {
		MarketCredential c = getCredential();
		return c != null && c.getRefreshToken() != null && !c.getRefreshToken().isBlank();
	}

	public String getValidAccessToken() {
		MarketCredential credential = getCredential();
		if (credential == null) {
			throw new IllegalStateException("Cafe24 credential 미등록 — 재인증이 필요합니다");
		}
		if (isTokenValid(credential)) {
			return credential.getAccessToken();
		}
		String token = refreshLock.runExclusively(CAFE24_TOKEN_LOCK_KEY, () -> {
			MarketCredential fresh = getCredential();
			if (fresh != null && isTokenValid(fresh)) {
				return fresh.getAccessToken();
			}
			return doRefresh(fresh);
		});
		if (token == null) {
			throw new IllegalStateException(
				"Cafe24 access token 획득 실패 — 재인증이 필요합니다(refresh token 만료/무효 또는 미발급)");
		}
		return token;
	}

	@Override
	public void refreshProactively() {
		MarketCredential credential = getCredential();
		if (credential == null
			|| credential.getRefreshToken() == null || credential.getRefreshToken().isBlank()) {
			log.info("Cafe24 선제 토큰 갱신 건너뜀 — refresh token 미보유(재인증 필요).");
			return;
		}
		try {
			refreshLock.runExclusively(CAFE24_TOKEN_LOCK_KEY, () -> doRefresh(getCredential()));
			log.info("✅ Cafe24 선제 토큰 갱신 완료 — 리프레시 토큰 회전(2주 시한 연장).");
		} catch (Exception e) {
			log.error("❌ Cafe24 선제 토큰 갱신 실패 — 재인증이 필요할 수 있습니다.", e);
		}
	}

	public String getApiUrl() {
		MarketCredential credential = getCredential();
		if (credential == null) {
			return null;
		}
		return "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
	}

	public String generateAuthorizationUrl(MarketCredential credential) {
		if (credential == null || credential.getClientId() == null
			|| !credential.getClientId().matches("[a-zA-Z0-9-]{1,100}")
			|| credential.getAccessKey() == null || credential.getAccessKey().isBlank()
			|| credential.getRedirectUri() == null || credential.getRedirectUri().isBlank())
			throw new IllegalArgumentException("카페24 Mall ID·Client ID·Redirect URI를 먼저 저장하세요.");
		String apiUrl = "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
		String scope = "mall.read_application,mall.write_application,"
			+ "mall.read_store,"
			+ "mall.read_product,mall.write_product,"
			+ "mall.read_collection,mall.write_collection,"
			+ "mall.read_category,mall.write_category,"
			+ "mall.read_order,mall.write_order,"
			+ "mall.read_shipping,mall.write_shipping";
		return String.format(
			"%s/oauth/authorize?response_type=code&client_id=%s&state=shouldbeshopping&redirect_uri=%s&scope=%s",
			apiUrl, form(credential.getAccessKey()), form(credential.getRedirectUri()), form(scope));
	}

	public String authorizationUrlForSavedCredential() {
		return generateAuthorizationUrl(getCredential());
	}

	private static String form(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	public void issueInitialToken(String code) {
		if (code == null || code.isBlank())
			throw new IllegalArgumentException("인증 코드가 비어 있습니다.");
		refreshLock.runExclusively(CAFE24_TOKEN_LOCK_KEY, () -> {
			MarketCredential credential = getCredential();
			if (credential == null)
				throw new IllegalStateException("Cafe24 credential 미등록 — 재인증이 필요합니다");
			String payload = String.format("grant_type=authorization_code&code=%s&redirect_uri=%s",
				form(code), form(credential.getRedirectUri()));
			var resp = tokenClient.exchange(credential.getClientId(), credential.getAccessKey(),
				credential.getSecretKey(), payload);
			persist(credential, resp);
			return true;
		});
		log.info("🎉 [최초 인증 성공] 토큰 3종이 발급·저장되었습니다.");
	}

	private MarketCredential getCredential() {
		return marketCredentialRepository.findByMarketType(MarketType.CAFE24).orElse(null);
	}

	private boolean isTokenValid(MarketCredential c) {
		if (c.getAccessToken() == null || c.getAccessToken().isBlank()
			|| c.getTokenExpiresAt() == null) {
			return false;
		}
		Instant expiry = c.getTokenExpiresAt().atZone(KST).toInstant();
		return expiry.minusSeconds(300).isAfter(Instant.now());
	}

	private String doRefresh(MarketCredential credential) {
		if (credential == null) {
			return null;
		}
		String refreshToken = credential.getRefreshToken();
		if (refreshToken == null || refreshToken.isBlank()) {
			return null;
		}
		try {
			var resp = tokenClient.exchange(
				credential.getClientId(), credential.getAccessKey(), credential.getSecretKey(),
				"grant_type=refresh_token&refresh_token=" + refreshToken);
			persist(credential, resp);
			log.info("✅ Cafe24 토큰 갱신 완료 (만료: {})", credential.getTokenExpiresAt());
			return resp.accessToken();
		} catch (Exception e) {
			log.error("❌ Cafe24 토큰 갱신 실패 — 재인증이 필요할 수 있습니다", e);
			throw new IllegalStateException(
				"Cafe24 토큰 갱신 실패 — 재인증이 필요합니다: " + e.getMessage(), e);
		}
	}

	private void persist(MarketCredential credential, Cafe24OAuthTokenClient.TokenResponse resp) {
		credential.setAccessToken(resp.accessToken());
		if (resp.refreshToken() != null && !resp.refreshToken().isBlank()) {
			credential.setRefreshToken(resp.refreshToken());
		}
		credential.setTokenExpiresAt(LocalDateTime.ofInstant(resp.expiresAt(), KST));
		marketCredentialRepository.save(credential);
	}
}

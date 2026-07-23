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

	private static final long CAFE24_TOKEN_LOCK_KEY = 0xCAFE24L; // 모든 프로세스 공통 상수
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MarketCredentialRepository marketCredentialRepository;
	private final Cafe24OAuthTokenClient tokenClient;
	private final TokenRefreshLock refreshLock;

	@PostConstruct
	public void init() {
		// startup 강제 refresh 폐지 — 불필요한 refresh_token 회전이 2 JVM 경쟁을 유발했음.
		MarketCredential c = getCredential();
		if (c == null || c.getClientId() == null || c.getSecretKey() == null) {
			log.warn("🚨 Cafe24 API 정보 미등록 — 설정 페이지에서 키를 입력하세요.");
		} else if (c.getRefreshToken() == null || c.getRefreshToken().isBlank()) {
			log.warn("🚨 Cafe24 재인증 필요 — 인증 URL: {}", generateAuthorizationUrl(c));
		} else {
			log.info("✅ Cafe24 자격증명 확인됨 — 토큰은 최초 사용 시 필요하면 갱신합니다.");
		}
	}

	private MarketCredential getCredential() {
		return marketCredentialRepository.findByMarketType(MarketType.CAFE24).orElse(null);
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
			MarketCredential fresh = getCredential(); // 락 획득 후 재조회(double-check)
			if (fresh != null && isTokenValid(fresh)) {
				return fresh.getAccessToken(); // 다른 프로세스가 이미 갱신함 — HTTP 생략
			}
			return doRefresh(fresh);
		});
		if (token == null) {
			throw new IllegalStateException(
				"Cafe24 access token 획득 실패 — 재인증이 필요합니다(refresh token 만료/무효 또는 미발급)");
		}
		return token;
	}

	/**
	 * D-103: 트래픽과 독립적으로 리프레시 토큰을 선제 회전한다(2주 시한 만료 방지).
	 * access token 유효 여부와 무관하게 refresh를 강제해 리프레시 토큰의 2주 시한을 매번 연장한다.
	 * 과거 startup 강제 refresh는 2 JVM 경쟁 때문에 폐지됐으나(init 주석 참조), 현재 단일 JVM +
	 * advisory lock 하에서는 안전하다. 실패는 삼켜 호출 스케줄러를 보호한다.
	 */
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
		// Cafe24가 refresh_token을 생략(null)할 수 있음 — 기존 값 보존
		if (resp.refreshToken() != null && !resp.refreshToken().isBlank()) {
			credential.setRefreshToken(resp.refreshToken());
		}
		credential.setTokenExpiresAt(LocalDateTime.ofInstant(resp.expiresAt(), KST));
		marketCredentialRepository.save(credential);
	}

	public String getApiUrl() {
		MarketCredential credential = getCredential();
		if (credential == null) {
			return null;
		}
		return "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
	}

	public String generateAuthorizationUrl(MarketCredential credential) {
		String apiUrl = "https://" + credential.getClientId() + ".cafe24api.com/api/v2";
		String scope = "mall.read_application,mall.write_application,"
			+ "mall.read_product,mall.write_product,"
			+ "mall.read_collection,mall.write_collection,"
			+ "mall.read_order,mall.write_order,"
			+ "mall.read_shipping,mall.write_shipping";
		return String.format(
			"%s/oauth/authorize?response_type=code&client_id=%s&state=shouldbeshopping&redirect_uri=%s&scope=%s",
			apiUrl, credential.getAccessKey(), credential.getRedirectUri(), scope);
	}

	public void issueInitialToken(String code) {
		MarketCredential credential = getCredential();
		if (credential == null) {
			throw new IllegalStateException("Cafe24 credential 미등록 — 재인증이 필요합니다");
		}
		String payload = String.format(
			"grant_type=authorization_code&code=%s&redirect_uri=%s",
			code, credential.getRedirectUri());
		var resp = tokenClient.exchange(
			credential.getClientId(), credential.getAccessKey(), credential.getSecretKey(), payload);
		persist(credential, resp);
		log.info("🎉 [최초 인증 성공] 토큰 3종이 발급·저장되었습니다.");
	}
}

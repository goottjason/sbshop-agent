package com.sbshop.agent.infrastructure.client.cafe24;

import java.time.Instant;

/** Cafe24 OAuth 토큰 교환 HTTP 호출 시임(테스트 대체 가능). */
public interface Cafe24OAuthTokenClient {

	/**
	 * @param mallId       Cafe24 Mall ID (MarketCredential.clientId)
	 * @param clientId     Cafe24 Client ID (MarketCredential.accessKey) — Basic auth 사용자
	 * @param clientSecret Cafe24 Client Secret (MarketCredential.secretKey)
	 * @param formPayload  grant_type=... 형태의 x-www-form-urlencoded 본문
	 */
	TokenResponse exchange(String mallId, String clientId, String clientSecret, String formPayload);

	record TokenResponse(String accessToken, String refreshToken, Instant expiresAt) {}
}

package com.sbshop.agent.infrastructure.client.cafe24;

import java.time.Instant;

public interface Cafe24OAuthTokenClient {

	TokenResponse exchange(String mallId, String clientId, String clientSecret, String formPayload);

	record TokenResponse(String accessToken, String refreshToken, Instant expiresAt) {
	}
}

package com.sbshop.agent.core.application.market.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketCredentialDtoMaskingTest {

	@Test
	@DisplayName("인증 게이트 하에서 시크릿 평문과 설정 여부 플래그를 함께 담는다")
	void exposesSecretPlaintextForAuthenticatedAdmin() {
		MarketCredentialDto dto = MarketCredentialDto.fromEntity(
			credential("VENDOR123", "ACCESS_PLAINTEXT", "SECRET_PLAINTEXT", "RT", "https://x/cb"));

		assertThat(dto.getAccessKey()).isEqualTo("ACCESS_PLAINTEXT");
		assertThat(dto.getSecretKey()).isEqualTo("SECRET_PLAINTEXT");
		assertThat(dto.getRefreshToken()).isEqualTo("RT");
		assertThat(dto.isHasSecretKey()).isTrue();
		assertThat(dto.isHasAccessKey()).isTrue();
		assertThat(dto.isHasRefreshToken()).isTrue();
		assertThat(dto.getClientId()).isEqualTo("VENDOR123");
		assertThat(dto.getRedirectUri()).isEqualTo("https://x/cb");
	}

	@Test
	@DisplayName("직렬화된 JSON에 시크릿 평문과 설정 여부 플래그가 모두 담긴다")
	void serializedJsonContainsSecretPlaintextAndFlags() throws Exception {
		MarketCredentialDto dto = MarketCredentialDto.fromEntity(
			credential("VENDOR123", "ACCESS_PLAINTEXT", "SECRET_PLAINTEXT", "RT_PLAINTEXT",
				"https://x/cb"));

		String json = new ObjectMapper().writeValueAsString(dto);

		assertThat(json).contains("ACCESS_PLAINTEXT");
		assertThat(json).contains("SECRET_PLAINTEXT");
		assertThat(json).contains("RT_PLAINTEXT");
		assertThat(json).contains("hasSecretKey").contains("hasAccessKey").contains("hasRefreshToken");
	}

	@Test
	@DisplayName("미설정 시크릿은 설정 여부 플래그가 false다")
	void flagsAreFalseWhenSecretsAbsent() {
		MarketCredentialDto dto = MarketCredentialDto.fromEntity(
			credential("VENDOR123", null, "  ", null, null));

		assertThat(dto.isHasAccessKey()).isFalse();
		assertThat(dto.isHasSecretKey()).isFalse();
		assertThat(dto.isHasRefreshToken()).isFalse();
	}

	private MarketCredential credential(String clientId, String accessKey, String secretKey,
		String refreshToken, String redirectUri) {
		return MarketCredential.builder()
			.marketType(MarketType.COUPANG)
			.clientId(clientId)
			.accessKey(accessKey)
			.secretKey(secretKey)
			.refreshToken(refreshToken)
			.redirectUri(redirectUri)
			.build();
	}
}

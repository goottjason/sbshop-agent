package com.sbshop.agent.core.application.market.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마켓 자격증명 응답 시크릿 노출 정책(2026-07-24 개정).
 *
 * <p>과거 F-CRED-1·7은 시크릿을 마스킹했으나, 그 근거는 <b>{@code /api/v1/market-credentials/**}가
 * 무인증 공개 엔드포인트</b>였기 때문이다. 이제 해당 경로는 {@code SecurityConfig}에서 관리자
 * HTTP Basic 인증으로 보호되므로, 인증된 관리자가 설정 화면에서 저장값을 확인·수정할 수 있도록
 * {@link MarketCredentialDto}가 시크릿 평문(accessKey·secretKey·refreshToken)을 담는다.
 * '설정 여부' 불리언(hasXxx)은 하위호환을 위해 함께 유지한다.
 *
 * <p>보안 불변식: 시크릿 노출의 안전성은 <b>엔드포인트 인증</b>이 보장한다(이 DTO가 아니라).
 * 인증을 제거하면 다시 노출 결함이 되므로 {@code /api/v1/market-credentials/**}의 authenticated()
 * 매처를 함께 유지해야 한다.
 */
class MarketCredentialDtoMaskingTest {

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

	@Test
	@DisplayName("인증 게이트 하에서 시크릿 평문과 설정 여부 플래그를 함께 담는다")
	void exposesSecretPlaintextForAuthenticatedAdmin() {
		MarketCredentialDto dto = MarketCredentialDto.fromEntity(
			credential("VENDOR123", "ACCESS_PLAINTEXT", "SECRET_PLAINTEXT", "RT", "https://x/cb"));

		// 시크릿 평문 노출(관리자 화면 확인·수정용)
		assertThat(dto.getAccessKey()).isEqualTo("ACCESS_PLAINTEXT");
		assertThat(dto.getSecretKey()).isEqualTo("SECRET_PLAINTEXT");
		assertThat(dto.getRefreshToken()).isEqualTo("RT");
		// 설정 여부 플래그도 유지(하위호환)
		assertThat(dto.isHasSecretKey()).isTrue();
		assertThat(dto.isHasAccessKey()).isTrue();
		assertThat(dto.isHasRefreshToken()).isTrue();
		// 식별자·리다이렉트는 평문 유지
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
}

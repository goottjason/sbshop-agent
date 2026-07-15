package com.sbshop.agent.core.application.market.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-CRED-1·7: 마켓 자격증명 응답 시크릿 마스킹.
 *
 * <p>{@link MarketCredentialDto}는 목록(GET /)·단건(GET /{marketType})·저장(PUT /{marketType})
 * 모든 응답의 공통 조립 지점이다. 결함 상태에서는 {@code secretKey}·{@code accessKey}를 평문으로
 * 담아 반환해 무인증 API로 전 마켓 시크릿이 노출됐다. {@code refreshToken}은 이미
 * {@code hasRefreshToken} 불리언으로 마스킹돼 있으며, 이 테스트는 동일 패턴을
 * {@code secretKey}·{@code accessKey}로 확장함을 고정한다.
 *
 * <p>{@code clientId}(Vendor ID·Mall ID 등 식별자)와 {@code redirectUri}는 민감도가 낮고
 * 프론트가 Cafe24 OAuth URL 조립에 사용하므로 평문 노출을 유지한다.
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
	@DisplayName("secretKey·accessKey 평문은 응답 DTO에 노출되지 않고 설정 여부 플래그로 대체된다")
	void doesNotExposeSecretPlaintext() {
		MarketCredentialDto dto = MarketCredentialDto.fromEntity(
			credential("VENDOR123", "ACCESS_PLAINTEXT", "SECRET_PLAINTEXT", "RT", "https://x/cb"));

		// 시크릿 평문 미노출: secretKey·accessKey 필드는 DTO에서 제거됨(getter 부재로 컴파일 단계에서 보증).
		// 설정 여부 플래그로 대체
		assertThat(dto.isHasSecretKey()).isTrue();
		assertThat(dto.isHasAccessKey()).isTrue();
		assertThat(dto.isHasRefreshToken()).isTrue();
		// 식별자·리다이렉트는 평문 유지(민감도 낮음, 프론트 OAuth 조립에 필요)
		assertThat(dto.getClientId()).isEqualTo("VENDOR123");
		assertThat(dto.getRedirectUri()).isEqualTo("https://x/cb");
	}

	@Test
	@DisplayName("직렬화된 JSON 응답에 시크릿 평문 문자열이 존재하지 않는다")
	void serializedJsonContainsNoSecretPlaintext() throws Exception {
		MarketCredentialDto dto = MarketCredentialDto.fromEntity(
			credential("VENDOR123", "ACCESS_PLAINTEXT", "SECRET_PLAINTEXT", "RT_PLAINTEXT",
				"https://x/cb"));

		String json = new ObjectMapper().writeValueAsString(dto);

		// 무인증 API로 노출되던 시크릿 평문이 응답 본문에 없어야 한다.
		assertThat(json).doesNotContain("ACCESS_PLAINTEXT");
		assertThat(json).doesNotContain("SECRET_PLAINTEXT");
		assertThat(json).doesNotContain("RT_PLAINTEXT");
		// 설정 여부 플래그는 담긴다.
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

package com.sbshop.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-MISC-17·7: 내부/무인증 트리거 엔드포인트의 공유시크릿 헤더 가드.
 * env 미설정(빈 토큰) 시 가드 비활성 → 무조건 통과(무파손).
 * env 설정 시 가드 활성 → 헤더 토큰이 시크릿과 정확히 일치할 때만 통과.
 */
class InternalAccessGuardTest {

	private InternalAccessGuard guard(String configuredToken) {
		return new InternalAccessGuard(configuredToken);
	}

	@Test
	@DisplayName("토큰 미설정(빈 문자열) → 가드 비활성: 헤더 없어도(null) 통과")
	void disabledWhenTokenBlank_allowsNull() {
		assertThat(guard("").isAllowed(null)).isTrue();
		assertThat(guard("   ").isAllowed(null)).isTrue();
	}

	@Test
	@DisplayName("토큰 미설정 → 가드 비활성: 임의 헤더 값도 통과")
	void disabledWhenTokenBlank_allowsAnyValue() {
		assertThat(guard("").isAllowed("whatever")).isTrue();
	}

	@Test
	@DisplayName("토큰 설정(가드 활성) → 헤더 없음(null) 거부")
	void enabled_rejectsMissingHeader() {
		assertThat(guard("s3cr3t").isAllowed(null)).isFalse();
	}

	@Test
	@DisplayName("토큰 설정(가드 활성) → 불일치 헤더 거부")
	void enabled_rejectsMismatch() {
		assertThat(guard("s3cr3t").isAllowed("wrong")).isFalse();
		assertThat(guard("s3cr3t").isAllowed("")).isFalse();
	}

	@Test
	@DisplayName("토큰 설정(가드 활성) → 정확히 일치하면 통과")
	void enabled_allowsExactMatch() {
		assertThat(guard("s3cr3t").isAllowed("s3cr3t")).isTrue();
	}
}

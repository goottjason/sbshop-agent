package com.sbshop.agent.core.domain.order.vo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-119: 마켓 동기화가 송장을 반영할 때 쓰는 "의미 있는 값" 판정.
 * D-107/108의 isMeaningfulPii와 같은 취지 — 마켓이 빈 값/자리표시자를 주는 경우
 * 그 값으로 우리 실값을 덮어쓰면 안 된다.
 */
class ShippingDataTrackingGuardTest {

	@Test
	@DisplayName("정상 송장번호는 의미 있는 값")
	void realTrackingNumber_isMeaningful() {
		assertThat(ShippingData.isMeaningfulTracking("424437727991")).isTrue();
	}

	@Test
	@DisplayName("하이픈 포함 송장번호도 의미 있는 값")
	void hyphenatedTrackingNumber_isMeaningful() {
		assertThat(ShippingData.isMeaningfulTracking("4244-3772-7991")).isTrue();
	}

	@Test
	@DisplayName("null·빈 문자열·공백은 의미 없음 (마켓 미입력)")
	void blank_isNotMeaningful() {
		assertThat(ShippingData.isMeaningfulTracking(null)).isFalse();
		assertThat(ShippingData.isMeaningfulTracking("")).isFalse();
		assertThat(ShippingData.isMeaningfulTracking("   ")).isFalse();
	}

	@Test
	@DisplayName("전부 0인 값은 자리표시자 — 의미 없음 (운영 DB에 실제 유입된 '00000000')")
	void allZeros_isNotMeaningful() {
		assertThat(ShippingData.isMeaningfulTracking("00000000")).isFalse();
		assertThat(ShippingData.isMeaningfulTracking("0")).isFalse();
		assertThat(ShippingData.isMeaningfulTracking("0000-0000")).isFalse();
	}
}

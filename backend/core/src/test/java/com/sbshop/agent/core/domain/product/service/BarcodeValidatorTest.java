package com.sbshop.agent.core.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BarcodeValidatorTest {

	@ParameterizedTest
	@ValueSource(strings = {"068958016375", "695927121213"})
	@DisplayName("iHerb 실측 UPC-A 12자리는 체크디짓 검증을 통과한다")
	void validate_upcA_isValid(String raw) {
		BarcodeValidator.Result result = BarcodeValidator.validate(raw);

		assertThat(result.valid()).isTrue();
		assertThat(result.normalized()).isEqualTo(raw);
		assertThat(result.reason()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"5021265244171", "5021265246335", "5021265223305"})
	@DisplayName("vitabiotics 실측 EAN-13 13자리는 체크디짓 검증을 통과한다")
	void validate_ean13_isValid(String raw) {
		assertThat(BarcodeValidator.validate(raw).valid()).isTrue();
	}

	@Test
	@DisplayName("EAN-8 8자리도 동일한 GS1 mod-10 규칙으로 통과한다")
	void validate_ean8_isValid() {
		assertThat(BarcodeValidator.validate("96385074").valid()).isTrue();
	}

	@Test
	@DisplayName("체크디짓이 틀리면 거부하고 사유를 남긴다")
	void validate_wrongCheckDigit_isRejectedWithReason() {
		BarcodeValidator.Result result = BarcodeValidator.validate("068958016374");

		assertThat(result.valid()).isFalse();
		assertThat(result.normalized()).isNull();
		assertThat(result.reason()).contains("체크디짓");
	}

	@Test
	@DisplayName("EAN-13 체크디짓이 틀리면 거부한다")
	void validate_ean13WrongCheckDigit_isRejected() {
		assertThat(BarcodeValidator.validate("5021265244172").valid()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"12345678901", "1234567890123456", "0123456789012345"})
	@DisplayName("8·12·13 이외의 자릿수는 지원 표준이 아니라 거부한다")
	void validate_unsupportedLength_isRejected(String raw) {
		BarcodeValidator.Result result = BarcodeValidator.validate(raw);

		assertThat(result.valid()).isFalse();
		assertThat(result.reason()).contains("자릿수");
	}

	@Test
	@DisplayName("GTIN-14(물류 박스 코드)는 체크디짓이 맞아도 소매 바코드가 아니라 거부한다")
	void validate_gtin14_isRejected() {
		assertThat(BarcodeValidator.validate("10068958016372").valid()).isFalse();
	}

	@Test
	@DisplayName("공백·하이픈은 제거하고 검증한다")
	void validate_stripsSeparators() {
		BarcodeValidator.Result result = BarcodeValidator.validate(" 5021265-244171 ");

		assertThat(result.valid()).isTrue();
		assertThat(result.normalized()).isEqualTo("5021265244171");
	}

	@Test
	@DisplayName("숫자가 아닌 문자가 섞이면 거부한다")
	void validate_nonDigit_isRejected() {
		BarcodeValidator.Result result = BarcodeValidator.validate("50212652441A1");

		assertThat(result.valid()).isFalse();
		assertThat(result.reason()).contains("숫자");
	}

	@Test
	@DisplayName("null·빈 문자열은 '값 없음'으로 구분해 거부한다")
	void validate_blank_isRejectedAsAbsent() {
		assertThat(BarcodeValidator.validate(null).valid()).isFalse();
		assertThat(BarcodeValidator.validate("   ").valid()).isFalse();
		assertThat(BarcodeValidator.validate(null).absent()).isTrue();
		assertThat(BarcodeValidator.validate("   ").absent()).isTrue();
		assertThat(BarcodeValidator.validate("50212652441A1").absent()).isFalse();
	}

	@Test
	@DisplayName("normalizedOrEmpty는 유효할 때만 값을, 그 외에는 빈 문자열을 준다")
	void normalizedOrEmpty_onlyForValid() {
		assertThat(BarcodeValidator.normalizedOrEmpty("5021265-244171")).isEqualTo("5021265244171");
		assertThat(BarcodeValidator.normalizedOrEmpty("068958016374")).isEmpty();
		assertThat(BarcodeValidator.normalizedOrEmpty(null)).isEmpty();
	}
}

package com.sbshop.agent.core.domain.product.service;

import java.util.Set;

public final class BarcodeValidator {
	private static final Set<Integer> SUPPORTED_LENGTHS = Set.of(8, 12, 13);
	private static final String ABSENT_REASON = "바코드 값 없음";

	private BarcodeValidator() {}

	public record Result(boolean valid, String normalized, String reason, boolean absent) {
		public static Result ok(String normalized) {
			return new Result(true, normalized, null, false);
		}

		public static Result missing() {
			return new Result(false, null, ABSENT_REASON, true);
		}

		public static Result rejected(String reason) {
			return new Result(false, null, reason, false);
		}
	}

	public static Result validate(String raw) {
		if (raw == null || raw.isBlank()) {
			return Result.missing();
		}
		String digits = raw.replaceAll("[\\s-]", "");
		if (digits.isEmpty()) {
			return Result.missing();
		}
		if (!digits.chars().allMatch(Character::isDigit)) {
			return Result.rejected("바코드에 숫자가 아닌 문자가 있음: " + raw);
		}
		if (!SUPPORTED_LENGTHS.contains(digits.length())) {
			return Result.rejected(
				"지원하지 않는 바코드 자릿수 %d (EAN-8/UPC-A 12/EAN-13 만 허용): %s".formatted(digits.length(), digits));
		}
		if (checkDigit(digits) != digits.charAt(digits.length() - 1) - '0') {
			return Result.rejected("바코드 체크디짓 불일치: " + digits);
		}
		return Result.ok(digits);
	}

	public static String normalizedOrEmpty(String raw) {
		Result result = validate(raw);
		return result.valid() ? result.normalized() : "";
	}

	private static int checkDigit(String digits) {
		int sum = 0;
		int weight = 3;
		for (int i = digits.length() - 2; i >= 0; i--) {
			sum += (digits.charAt(i) - '0') * weight;
			weight = weight == 3 ? 1 : 3;
		}
		return (10 - sum % 10) % 10;
	}
}

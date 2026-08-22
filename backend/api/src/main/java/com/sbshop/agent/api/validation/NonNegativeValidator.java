package com.sbshop.agent.api.validation;

import java.math.BigDecimal;

public final class NonNegativeValidator {

	private NonNegativeValidator() {}

	public static void requireNonNegative(String label, BigDecimal value) {
		if (value != null && value.signum() < 0) {
			throw new IllegalArgumentException(label + "는 0 이상이어야 합니다: " + value);
		}
	}

	public static void requireNonNegative(String label, Integer value) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(label + "는 0 이상이어야 합니다: " + value);
		}
	}
}

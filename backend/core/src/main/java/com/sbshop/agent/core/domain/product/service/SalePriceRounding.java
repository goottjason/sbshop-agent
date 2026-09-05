package com.sbshop.agent.core.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Q18/Q20: 100원 반올림, 최소마진 하한 미달이면 하한을 충족하는 100원 단위 가격. */
public final class SalePriceRounding {

	private SalePriceRounding() {}

	public record Result(BigDecimal roundedPrice, BigDecimal minimumPrice, BigDecimal salePrice) {
		public boolean minimumAdjusted() {
			return salePrice.compareTo(roundedPrice) > 0;
		}

		public String reason() {
			return minimumAdjusted()
				? "최소마진 보장: 반올림가 " + text(roundedPrice) + "원 / 하한 " + text(minimumPrice)
					+ "원 → " + text(salePrice) + "원 (100원 단위 조정)"
				: "100원 단위 반올림";
		}
	}

	public static BigDecimal nearestHundred(BigDecimal price) {
		requireNonNegative(price, "판매가");
		return price.setScale(-2, RoundingMode.HALF_UP).setScale(0);
	}

	public static Result fromPrice(BigDecimal price, BigDecimal minimumPrice) {
		return protectMinimum(nearestHundred(price), minimumPrice);
	}

	public static Result fromRatio(BigDecimal numerator, BigDecimal divisor, BigDecimal minimumPrice) {
		requireNonNegative(numerator, "총 매입가");
		if (divisor == null || divisor.signum() <= 0) {
			throw new IllegalArgumentException("마진율과 채널수수료의 합은 100% 미만이어야 합니다.");
		}
		// 나눗셈을 원 단위로 먼저 반올림하지 않고 100원 단위에서 한 번만 반올림한다.
		return protectMinimum(numerator.divide(divisor, -2, RoundingMode.HALF_UP).setScale(0), minimumPrice);
	}

	private static Result protectMinimum(BigDecimal roundedPrice, BigDecimal minimumPrice) {
		if (minimumPrice != null)
			requireNonNegative(minimumPrice, "최소 판매가");
		BigDecimal finalPrice = minimumPrice != null && roundedPrice.compareTo(minimumPrice) < 0
			? minimumPrice.setScale(-2, RoundingMode.CEILING).setScale(0) : roundedPrice;
		return new Result(roundedPrice, minimumPrice, finalPrice);
	}

	private static void requireNonNegative(BigDecimal value, String label) {
		if (value == null || value.signum() < 0)
			throw new IllegalArgumentException(label + "는 0 이상이어야 합니다.");
	}

	private static String text(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}

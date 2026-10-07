package com.sbshop.agent.core.domain.pricing;

import java.math.BigDecimal;

/** 가격 정책의 null(기본값 사용)과 0(할인·배송료 미적용)은 유지한다. */
public final class PricePolicyValidator {

	private static final BigDecimal MAX_MARGIN_RATE = new BigDecimal("99.99");
	private static final BigDecimal MAX_COUPON_RATE = new BigDecimal("100");
	private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999.99");
	private static final BigDecimal MAX_SHIPPING_AMOUNT = new BigDecimal("99999999.99");

	private PricePolicyValidator() {}

	public static void validate(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		validateRange("마진율", marginRate, MAX_MARGIN_RATE);
		validateRange("쿠폰율", couponRate, MAX_COUPON_RATE);
		validateRange("최소마진", minMarginPrice, MAX_PRICE);
	}

	public static void validateShipping(BigDecimal shipBaseAmount, Integer shipBaseWeightG,
		BigDecimal shipStepAmount, Integer shipStepWeightG, BigDecimal domesticFee,
		BigDecimal domesticFreeOver) {
		validateRange("기본 배송료", shipBaseAmount, MAX_SHIPPING_AMOUNT);
		validateWeight("기본 배송 무게", shipBaseWeightG);
		validateRange("추가 배송료", shipStepAmount, MAX_SHIPPING_AMOUNT);
		validateWeight("추가 배송 무게", shipStepWeightG);
		validateRange("국내 배송료", domesticFee, MAX_SHIPPING_AMOUNT);
		validateRange("국내 무료배송 기준금액", domesticFreeOver, MAX_PRICE);
	}

	private static void validateRange(String label, BigDecimal value, BigDecimal maximum) {
		if (value != null && (value.signum() < 0 || value.compareTo(maximum) > 0)) {
			throw new IllegalArgumentException(label + "은(는) 0 이상 " + maximum.toPlainString()
				+ " 이하이어야 합니다.");
		}
	}

	private static void validateWeight(String label, Integer value) {
		if (value != null && value < 0) {
			throw new IllegalArgumentException(label + "는 0 이상이어야 합니다.");
		}
	}
}

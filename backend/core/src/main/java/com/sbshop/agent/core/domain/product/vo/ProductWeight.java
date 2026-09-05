package com.sbshop.agent.core.domain.product.vo;

import java.math.BigDecimal;

/** 상품의 저장 무게는 kg. 기존 데이터의 단위를 값의 크기로 추측하지 않는다. */
public final class ProductWeight {

	// 초안의 g 소수 둘째 자리(0.01g)를 손실 없이 저장하며 기존 정수 8자리 범위를 유지한다.
	public static final int PRECISION = 13;
	public static final int SCALE = 5;
	private static final BigDecimal MAX_KG = new BigDecimal("99999999.99999");

	private ProductWeight() {}

	public static BigDecimal fromGrams(BigDecimal grams) {
		return requireKilograms(grams == null ? null : grams.movePointLeft(3));
	}

	/** null은 미입력이며, 입력한 값의 초과 정밀도는 임의로 반올림하지 않는다. */
	public static BigDecimal requireKilograms(BigDecimal kilograms) {
		if (kilograms == null)
			return null;
		if (kilograms.signum() < 0 || kilograms.compareTo(MAX_KG) > 0) {
			throw new IllegalArgumentException("무게는 0~99999999.99999kg 범위여야 합니다.");
		}
		if (kilograms.stripTrailingZeros().scale() > SCALE) {
			throw new IllegalArgumentException("무게는 kg 기준 소수 5자리까지 정확히 저장할 수 있습니다. 단위와 값을 확인하세요.");
		}
		return kilograms;
	}
}

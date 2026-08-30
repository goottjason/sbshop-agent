package com.sbshop.agent.core.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DomesticFeeFromPolicyTest {

	private final MarginCalculator calculator = new MarginCalculator();

	private static final BigDecimal MARGIN = new BigDecimal("25");
	private static final BigDecimal NO_COUPON = BigDecimal.ZERO;
	private static final BigDecimal MIN_MARGIN = new BigDecimal("5000");
	private static final BigDecimal FEE = new BigDecimal("11");

	@Test
	@DisplayName("국내 배송비가 0 인 소싱처는 4만원 미만이어도 붙지 않는다 — 영국 소싱처")
	void noDomesticFeeVendor() {
		BigDecimal withFee = calculator.calculateSalePrice(new BigDecimal("30000"), 1,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, new BigDecimal("6000"), new BigDecimal("40000"));
		BigDecimal without = calculator.calculateSalePrice(new BigDecimal("30000"), 1,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, BigDecimal.ZERO, BigDecimal.ZERO);

		assertThat(without).isLessThan(withFee);
	}

	@Test
	@DisplayName("무료 기준액 이상이면 붙지 않는다 — 아이허브 4만원 이상")
	void aboveFreeThreshold() {
		BigDecimal below = calculator.calculateSalePrice(new BigDecimal("39000"), 1,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, new BigDecimal("6000"), new BigDecimal("40000"));
		BigDecimal above = calculator.calculateSalePrice(new BigDecimal("41000"), 1,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, new BigDecimal("6000"), new BigDecimal("40000"));

		assertThat(above.subtract(below)).isLessThan(new BigDecimal("6000"));
	}

	@Test
	@DisplayName("정책 값이 화면에서 바뀌면 계산도 따라간다 — 6,000 → 9,000")
	void followsPolicyValue() {
		BigDecimal at6000 = calculator.calculateSalePrice(new BigDecimal("30000"), 1,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, new BigDecimal("6000"), new BigDecimal("40000"));
		BigDecimal at9000 = calculator.calculateSalePrice(new BigDecimal("30000"), 1,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, new BigDecimal("9000"), new BigDecimal("40000"));

		assertThat(at9000).isGreaterThan(at6000);
	}

	@Test
	@DisplayName("묶음 수량을 곱한 총 매입가로 기준을 판단한다 — 15,000 × 3 = 45,000 이면 무료")
	void thresholdUsesTotalNotUnit() {
		BigDecimal bundled = calculator.calculateSalePrice(new BigDecimal("15000"), 3,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, new BigDecimal("6000"), new BigDecimal("40000"));
		BigDecimal noFee = calculator.calculateSalePrice(new BigDecimal("15000"), 3,
			MARGIN, NO_COUPON, MIN_MARGIN, FEE, BigDecimal.ZERO, BigDecimal.ZERO);

		assertThat(bundled).isEqualByComparingTo(noFee);
	}
}

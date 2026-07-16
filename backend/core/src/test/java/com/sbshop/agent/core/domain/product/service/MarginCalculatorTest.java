package com.sbshop.agent.core.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarginCalculatorTest {

	private final MarginCalculator calculator = new MarginCalculator();

	@Test
	@DisplayName("구매가 40000원 미만이면 배송비 6000원이 추가된다")
	void calculateSalePrice_addsDeliveryFeeWhenUnder40000() {
		BigDecimal buyPrice = new BigDecimal("30000");
		BigDecimal salePrice = calculator.calculateSalePrice(
			buyPrice, 1, new BigDecimal("15"), new BigDecimal("5000"));

		assertThat(salePrice.intValue()).isGreaterThan(36000);
	}

	@Test
	@DisplayName("구매가 40000원 이상이면 배송비가 추가되지 않는다")
	void calculateSalePrice_noDeliveryFeeWhenOver40000() {
		BigDecimal buyPrice = new BigDecimal("50000");
		BigDecimal salePrice = calculator.calculateSalePrice(
			buyPrice, 1, new BigDecimal("15"), new BigDecimal("5000"));

		assertThat(salePrice.intValue()).isGreaterThan(50000);
	}

	@Test
	@DisplayName("특가 상품(discountType=2)은 discountPrice를 원가로 사용한다")
	void getEffectiveBuyPrice_specialDiscount_returnsDiscountPrice() {
		BigDecimal result = calculator.getEffectiveBuyPrice(
			new BigDecimal("50000"), new BigDecimal("35000"), 2, 10, 15);
		assertThat(result).isEqualByComparingTo("35000");
	}

	@Test
	@DisplayName("일반 상품은 쿠폰율과 판매할인율 중 큰 값을 적용한다")
	void getEffectiveBuyPrice_normalDiscount_usesMaxRate() {
		BigDecimal result = calculator.getEffectiveBuyPrice(
			new BigDecimal("50000"), null, 0, 20, 15);
		assertThat(result).isEqualByComparingTo("40000");
	}

	@Test
	@DisplayName("판매가는 100원 단위로 올림된다")
	void calculateSalePrice_roundsToNearest100() {
		BigDecimal salePrice = calculator.calculateSalePrice(
			new BigDecimal("33000"), 1, new BigDecimal("15"), null);
		assertThat(salePrice.remainder(new BigDecimal("100"))).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("쿠폰율을 적용하면 구매가가 낮아진 실매입가로 판매가를 산정한다 (F-BATCH-6)")
	void calculateSalePrice_withCoupon_lowersEffectiveBuyPrice() {
		// 구매가 10000, 쿠폰 20% → 실매입가 8000. 마진 10%, 최소마진 3500.
		BigDecimal withCoupon = calculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("3500"));
		// 쿠폰 적용 경로 = 실매입가 8000으로 기존 계산한 것과 동일해야 한다.
		BigDecimal manual = calculator.calculateSalePrice(
			new BigDecimal("8000"), 1, new BigDecimal("10"), new BigDecimal("3500"));
		assertThat(withCoupon).isEqualByComparingTo(manual);
		assertThat(withCoupon).isEqualByComparingTo("19600");
	}

	@Test
	@DisplayName("쿠폰율이 0이거나 null이면 판매가가 변하지 않는다 (하위호환)")
	void calculateSalePrice_zeroCoupon_unchanged() {
		BigDecimal base = calculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("10"), new BigDecimal("3500"));
		assertThat(calculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("3500")))
			.isEqualByComparingTo(base);
		assertThat(calculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("10"), null, new BigDecimal("3500")))
			.isEqualByComparingTo(base);
	}
}

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
}

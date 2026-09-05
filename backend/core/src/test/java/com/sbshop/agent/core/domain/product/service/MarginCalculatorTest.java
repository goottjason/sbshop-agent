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
	@DisplayName("판매가는 100원 단위로 반올림된다")
	void calculateSalePrice_roundsToNearest100() {
		BigDecimal salePrice = calculator.calculateSalePrice(
			new BigDecimal("33000"), 1, new BigDecimal("15"), null);
		assertThat(salePrice.remainder(new BigDecimal("100"))).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("쿠폰율을 적용하면 구매가가 낮아진 실매입가로 판매가를 산정한다 (F-BATCH-6)")
	void calculateSalePrice_withCoupon_lowersEffectiveBuyPrice() {
		BigDecimal withCoupon = calculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("3500"));

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

	@Test
	@DisplayName("D-094: 채널수수료를 파라미터로 받으면 마켓별 실수수료로 판매가를 산정한다")
	void calculateSalePrice_withChannelFee_usesMarketSpecificFee() {
		BigDecimal buyPrice = new BigDecimal("31522");

		BigDecimal coupang = calculator.calculateSalePrice(
			buyPrice, 2, new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("3500"), new BigDecimal("11"));
		assertThat(coupang).isEqualByComparingTo("67800");

		BigDecimal gmarket = calculator.calculateSalePrice(
			buyPrice, 2, new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("3500"), new BigDecimal("18"));
		assertThat(gmarket).isEqualByComparingTo("74400");

		assertThat(coupang).isLessThan(gmarket);
	}

	@Test
	@DisplayName("D-094: 수수료 파라미터 없는 기존 시그니처는 18.5% 고정을 유지한다 (하위호환)")
	void calculateSalePrice_withoutFeeParam_keeps18_5Default() {
		BigDecimal legacy = calculator.calculateSalePrice(
			new BigDecimal("31522"), 2, new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("3500"));
		assertThat(legacy).isEqualByComparingTo("74900");
	}

	@Test
	void quotePreservesMinimumAndExplainsTheHundredWonAdjustment() {
		var quote = calculator.quoteSalePrice(new BigDecimal("12340"), 1, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
		assertThat(quote.roundedPrice()).isEqualByComparingTo("12300");
		assertThat(quote.minimumPrice()).isEqualByComparingTo("12340");
		assertThat(quote.salePrice()).isEqualByComparingTo("12400");
		assertThat(quote.reason()).contains("최소마진 보장", "12340", "12400");
		assertThat(calculator.calculateSalePrice(new BigDecimal("12340"), 1, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null)).isEqualByComparingTo(quote.salePrice());
	}

	@Test
	void couponBundleAndDomesticShippingAreIncludedInTheExistingMinimumDefinition() {
		var quote = calculator.quoteSalePrice(new BigDecimal("10000"), 2, BigDecimal.ZERO, new BigDecimal("10"),
			new BigDecimal("2340"), BigDecimal.ZERO, new BigDecimal("6000"), new BigDecimal("40000"));
		assertThat(quote.minimumPrice()).isEqualByComparingTo("26340");
		assertThat(quote.salePrice()).isEqualByComparingTo("26400");
	}

	@Test
	void roundingToZeroCannotBecomeAnAutomaticSalePrice() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> calculator.quoteSalePrice(new BigDecimal("1"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, null))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0원");
	}
}

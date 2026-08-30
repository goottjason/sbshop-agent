package com.sbshop.agent.core.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LandedCostCalculatorTest {

	private static final BigDecimal FX = new BigDecimal("1868.34");

	private VendorPricePolicy ukPolicy() {
		return VendorPricePolicy.builder()
			.vendor(VendorType.COK).shipCurrency("GBP")
			.shipBaseAmount(new BigDecimal("10.5")).shipBaseWeightG(500)
			.shipStepAmount(new BigDecimal("2.0")).shipStepWeightG(500)
			.build();
	}

	@Test
	@DisplayName("상품 무게로 배송비를 매긴다 — 1.2kg 은 기초 + 2단위")
	void usesProductWeight() {
		BigDecimal goods = new BigDecimal("31556");

		BigDecimal buyPrice = LandedCostCalculator.buyPricePerUnit(
			goods, new BigDecimal("1.2"), 1, ukPolicy(), FX);

		BigDecimal shipping = new BigDecimal("14.5").multiply(FX)
			.setScale(0, java.math.RoundingMode.HALF_UP);
		assertThat(buyPrice).isEqualByComparingTo(goods.add(shipping));
	}

	@Test
	@DisplayName("배송비는 주문당 1회라 묶음수량으로 나눈다")
	void shippingSplitsAcrossBundle() {
		BigDecimal goods = new BigDecimal("10000");

		BigDecimal one = LandedCostCalculator.buyPricePerUnit(goods, new BigDecimal("0.3"), 1, ukPolicy(), FX);
		BigDecimal three = LandedCostCalculator.buyPricePerUnit(goods, new BigDecimal("0.3"), 3, ukPolicy(), FX);

		BigDecimal shipping = new BigDecimal("10.5").multiply(FX);
		assertThat(one.subtract(goods)).isEqualByComparingTo(shipping.setScale(0, java.math.RoundingMode.HALF_UP));
		assertThat(three.subtract(goods)).isEqualByComparingTo(
			shipping.divide(new BigDecimal("3"), 0, java.math.RoundingMode.HALF_UP));
	}

	@Test
	@DisplayName("해외 배송비가 없는 소싱처는 환율 없이도 계산된다 — 아이허브는 원화라 환율을 안 받는다")
	void noShippingVendorNeedsNoFx() {
		VendorPricePolicy iherb = VendorPricePolicy.builder()
			.vendor(VendorType.IHB).shipCurrency("KRW")
			.shipBaseAmount(BigDecimal.ZERO).build();

		assertThat(LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("29900"), null, 1, iherb, null)).isEqualByComparingTo("29900");
	}

	@Test
	@DisplayName("해외 배송비가 없는 소싱처는 원가가 곧 매입가다 — 아이허브")
	void noShippingVendor() {
		VendorPricePolicy iherb = VendorPricePolicy.builder()
			.vendor(VendorType.IHB).shipCurrency("KRW")
			.shipBaseAmount(BigDecimal.ZERO).shipBaseWeightG(500)
			.shipStepAmount(BigDecimal.ZERO).shipStepWeightG(500)
			.build();

		BigDecimal buyPrice = LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("29900"), new BigDecimal("0.4"), 1, iherb, BigDecimal.ONE);

		assertThat(buyPrice).isEqualByComparingTo("29900");
	}

	@Test
	@DisplayName("무게가 없으면 던진다 — 기초 배송비로 때우면 무거운 상품이 조용히 저가가 된다")
	void refusesMissingWeight() {
		assertThatThrownBy(() -> LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("10000"), null, 1, ukPolicy(), FX))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("무게");

		assertThatThrownBy(() -> LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("10000"), BigDecimal.ZERO, 1, ukPolicy(), FX))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("소싱처 정책이 없으면 던진다 — 배송비 0 으로 계산하지 않는다")
	void refusesMissingPolicy() {
		assertThatThrownBy(() -> LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("10000"), new BigDecimal("1.2"), 1, null, FX))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("정책");
	}

	@Test
	@DisplayName("환율이 없거나 0 이면 던진다 — 배송비가 0 이 되어버린다")
	void refusesMissingFx() {
		assertThatThrownBy(() -> LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("10000"), new BigDecimal("1.2"), 1, ukPolicy(), null))
			.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(() -> LandedCostCalculator.buyPricePerUnit(
			new BigDecimal("10000"), new BigDecimal("1.2"), 1, ukPolicy(), BigDecimal.ZERO))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("원가가 없으면 던진다 — 배송비만 남은 매입가는 의미가 없다")
	void refusesMissingGoods() {
		assertThatThrownBy(() -> LandedCostCalculator.buyPricePerUnit(
			null, new BigDecimal("1.2"), 1, ukPolicy(), FX))
			.isInstanceOf(IllegalStateException.class);
	}
}

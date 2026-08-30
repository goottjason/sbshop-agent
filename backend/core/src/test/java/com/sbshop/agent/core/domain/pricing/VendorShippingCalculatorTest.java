package com.sbshop.agent.core.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VendorShippingCalculatorTest {

	private VendorPricePolicy ukPolicy() {
		return VendorPricePolicy.builder()
			.vendor(VendorType.VTB)
			.shipCurrency("GBP")
			.shipBaseAmount(new BigDecimal("10.5"))
			.shipBaseWeightG(500)
			.shipStepAmount(new BigDecimal("2.0"))
			.shipStepWeightG(500)
			.build();
	}

	@Test
	@DisplayName("기초 무게 이하면 기초 배송비만 든다")
	void underBaseWeight() {
		assertThat(VendorShippingCalculator.amount(300.0, ukPolicy())).isEqualByComparingTo("10.5");
	}

	@Test
	@DisplayName("기초 무게와 정확히 같으면 아직 추가 요금이 붙지 않는다 — 경계값")
	void exactlyBaseWeight() {
		assertThat(VendorShippingCalculator.amount(500.0, ukPolicy())).isEqualByComparingTo("10.5");
	}

	@Test
	@DisplayName("기초 무게를 1g만 넘어도 추가 단위 하나가 통째로 붙는다")
	void justOverBaseWeight() {
		assertThat(VendorShippingCalculator.amount(501.0, ukPolicy())).isEqualByComparingTo("12.5");
	}

	@Test
	@DisplayName("추가 단위는 올림으로 센다 — 680g 는 500 + 180 이라 한 단위")
	void ceilsPartialStep() {
		assertThat(VendorShippingCalculator.amount(680.0, ukPolicy())).isEqualByComparingTo("12.5");
	}

	@Test
	@DisplayName("1.6kg 이면 기초 500g + 추가 3단위")
	void multipleSteps() {
		assertThat(VendorShippingCalculator.amount(1600.0, ukPolicy())).isEqualByComparingTo("16.5");
	}

	@Test
	@DisplayName("무게를 모르면 기초 배송비로 본다 — 계산을 포기하지 않는다")
	void unknownWeightFallsBackToBase() {
		assertThat(VendorShippingCalculator.amount(null, ukPolicy())).isEqualByComparingTo("10.5");
		assertThat(VendorShippingCalculator.amount(0.0, ukPolicy())).isEqualByComparingTo("10.5");
	}

	@Test
	@DisplayName("해외 배송비가 없는 소싱처는 0이다 — 아이허브")
	void noShippingVendor() {
		VendorPricePolicy iherb = VendorPricePolicy.builder()
			.vendor(VendorType.IHB).shipCurrency("KRW")
			.shipBaseAmount(BigDecimal.ZERO).shipBaseWeightG(500)
			.shipStepAmount(BigDecimal.ZERO).shipStepWeightG(500)
			.build();
		assertThat(VendorShippingCalculator.amount(1600.0, iherb)).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("정책이 없으면 null 을 돌려준다 — 0 으로 단정하면 배송비를 조용히 빠뜨린다")
	void nullPolicyYieldsNull() {
		assertThat(VendorShippingCalculator.amount(1600.0, null)).isNull();
	}

	@Test
	@DisplayName("추가 단위 무게가 0이면 기초 배송비만 든다 — 0으로 나누지 않는다")
	void zeroStepWeightDoesNotDivideByZero() {
		VendorPricePolicy broken = VendorPricePolicy.builder()
			.vendor(VendorType.OCD).shipCurrency("GBP")
			.shipBaseAmount(new BigDecimal("10.5")).shipBaseWeightG(500)
			.shipStepAmount(new BigDecimal("2.0")).shipStepWeightG(0)
			.build();
		assertThat(VendorShippingCalculator.amount(5000.0, broken)).isEqualByComparingTo("10.5");
	}
}

package com.sbshop.agent.core.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-360 6-3 고정 결정 3·5 — 배대지 배송비 = 기본 + 단계 × ⌈max(0, 계산 무게 − 기본 무게) ÷ 단계 무게⌉. */
class ForwarderShippingTest {
	/** 오레곤 배대지 1등급: 0.5kg 10,500원, 0.5kg마다 2,600원, 포장 가산 200g. */
	private static final ForwarderShipping.Rate OREGON = new ForwarderShipping.Rate(new BigDecimal("10500"), 500,
		new BigDecimal("2600"), 500, 200);

	@Test
	@DisplayName("예 A: 0.3kg × 묶음 2 + 포장 200g = 800g → 13,100원")
	void exampleA_bundleIsMultipliedIntoWeight() {
		var quote = ForwarderShipping.quote(OREGON, new BigDecimal("0.3"), 2);

		assertThat(quote.available()).isTrue();
		assertThat(quote.amount()).isEqualByComparingTo("13100");
		assertThat(quote.chargeableGrams()).isEqualByComparingTo("800");
	}

	@Test
	@DisplayName("예 D: 0.3kg × 1 + 포장 200g = 500g → 기본 금액 10,500원")
	void exampleD_baseOnly() {
		var quote = ForwarderShipping.quote(OREGON, new BigDecimal("0.3"), 1);

		assertThat(quote.amount()).isEqualByComparingTo("10500");
		assertThat(quote.chargeableGrams()).isEqualByComparingTo("500");
	}

	@Test
	@DisplayName("계산 무게 500g은 기본, 501g은 한 단계 올라간다")
	void halfKilogramBoundary() {
		assertThat(ForwarderShipping.quote(OREGON, new BigDecimal("0.300"), 1).amount())
			.isEqualByComparingTo("10500");
		assertThat(ForwarderShipping.quote(OREGON, new BigDecimal("0.301"), 1).amount())
			.isEqualByComparingTo("13100");
		assertThat(ForwarderShipping.quote(OREGON, new BigDecimal("0.30001"), 1).amount())
			.isEqualByComparingTo("13100");
	}

	@Test
	@DisplayName("포장 가산이 계산 무게에 더해진다 — 0.35kg은 포장 없이 기본, 포장 200g이면 한 단계")
	void packagingIsAdded() {
		var noPackaging = new ForwarderShipping.Rate(new BigDecimal("10500"), 500, new BigDecimal("2600"), 500,
			null);

		assertThat(ForwarderShipping.quote(noPackaging, new BigDecimal("0.35"), 1).amount())
			.isEqualByComparingTo("10500");
		assertThat(ForwarderShipping.quote(OREGON, new BigDecimal("0.35"), 1).amount())
			.isEqualByComparingTo("13100");
	}

	@Test
	@DisplayName("무거운 상품은 단계 수만큼 더한다 — 2.5kg × 2 + 200g = 5,200g → 10,500 + 2,600 × 10")
	void manySteps() {
		var quote = ForwarderShipping.quote(OREGON, new BigDecimal("2.5"), 2);

		assertThat(quote.amount()).isEqualByComparingTo("36500");
	}

	@Test
	@DisplayName("기본 금액이 비어 있거나 0이면 배대지 배송비는 0이다 — 무게가 없어도 계산한다")
	void noBaseAmountMeansNoForwarderFee() {
		var empty = new ForwarderShipping.Rate(null, 500, new BigDecimal("2600"), 500, 200);
		var zero = new ForwarderShipping.Rate(BigDecimal.ZERO, 500, new BigDecimal("2600"), 500, 200);

		for (var rate : new ForwarderShipping.Rate[] {empty, zero, null}) {
			var quote = ForwarderShipping.quote(rate, null, 1);
			assertThat(quote.available()).isTrue();
			assertThat(quote.amount()).isEqualByComparingTo("0");
			assertThat(quote.note()).isNull();
		}
	}

	@Test
	@DisplayName("요금이 있는데 상품 무게가 없거나 0이면 계산하지 않고 사유를 돌려준다 — 기본 요금으로 때우지 않는다")
	void missingWeightIsUnavailable() {
		for (var weight : new BigDecimal[] {null, BigDecimal.ZERO}) {
			var quote = ForwarderShipping.quote(OREGON, weight, 1);
			assertThat(quote.available()).isFalse();
			assertThat(quote.amount()).isNull();
			assertThat(quote.unavailableReason()).contains("무게");
		}
	}

	@Test
	@DisplayName("요금표의 기본 무게·단계 무게·단계 금액이 비면 계산하지 않는다")
	void incompleteRateIsUnavailable() {
		var noStepWeight = new ForwarderShipping.Rate(new BigDecimal("10500"), 500, new BigDecimal("2600"), null,
			200);
		var noStepAmount = new ForwarderShipping.Rate(new BigDecimal("10500"), 500, null, 500, 200);
		var noBaseWeight = new ForwarderShipping.Rate(new BigDecimal("10500"), null, new BigDecimal("2600"), 500,
			200);

		for (var rate : new ForwarderShipping.Rate[] {noStepWeight, noStepAmount, noBaseWeight}) {
			var quote = ForwarderShipping.quote(rate, new BigDecimal("0.3"), 1);
			assertThat(quote.available()).isFalse();
			assertThat(quote.unavailableReason()).contains("요금표");
		}
	}

	@Test
	@DisplayName("계산 근거에 배대지 배송비와 계산 무게 식을 남긴다")
	void noteShowsAmountAndWeightFormula() {
		var quote = ForwarderShipping.quote(OREGON, new BigDecimal("0.3"), 2);

		assertThat(quote.note()).isEqualTo("배대지 배송비 13,100원 · 계산 무게 800g = 300g × 2 + 포장 200g");
	}
}

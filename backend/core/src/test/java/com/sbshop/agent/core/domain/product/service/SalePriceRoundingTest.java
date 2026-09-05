package com.sbshop.agent.core.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SalePriceRoundingTest {
	@ParameterizedTest
	@CsvSource({"12345,12300", "12650,12700", "12300,12300", "12349.5,12300", "3000000000,3000000000"})
	void nearestHundredDoesNotPreRoundOrOverflow(String raw, String expected) {
		assertThat(SalePriceRounding.nearestHundred(new BigDecimal(raw))).isEqualByComparingTo(expected);
	}

	@ParameterizedTest
	@CsvSource({"12340,12400,true", "12300,12300,false", "12300.01,12400,true", "12500,12500,true",
		"12200,12300,false"})
	void minimumHasPriorityAndDoesNotAddAnotherHundredWhenAligned(String minimum, String expected, boolean adjusted) {
		var result = SalePriceRounding.fromPrice(new BigDecimal("12345"), new BigDecimal(minimum));
		assertThat(result.roundedPrice()).isEqualByComparingTo("12300");
		assertThat(result.salePrice()).isEqualByComparingTo(expected);
		assertThat(result.minimumAdjusted()).isEqualTo(adjusted);
		assertThat(result.salePrice()).isGreaterThanOrEqualTo(result.minimumPrice());
		assertThat(result.salePrice().remainder(new BigDecimal("100"))).isEqualByComparingTo("0");
		if (adjusted)
			assertThat(result.reason()).contains("최소마진 보장", minimum, expected);
	}

	@Test
	void divisionRoundsAtHundredsWithoutAnApproximateIntermediatePrice() {
		assertThat(
			SalePriceRounding.fromRatio(new BigDecimal("9879.999999999"), new BigDecimal("0.8"), null).salePrice())
			.isEqualByComparingTo("12300");
		assertThat(SalePriceRounding.fromRatio(new BigDecimal("9880"), new BigDecimal("0.8"), null).salePrice())
			.isEqualByComparingTo("12400");
	}

	@Test
	void invalidInputsCannotProduceASuccessfulPrice() {
		assertThatThrownBy(() -> SalePriceRounding.fromRatio(BigDecimal.TEN, BigDecimal.ZERO, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SalePriceRounding.fromPrice(new BigDecimal("-1"), null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}

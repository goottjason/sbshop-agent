package com.sbshop.agent.core.application.product.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NumericChangeCalculatorTest {
	@ParameterizedTest
	@CsvSource({
		"SALE_PRICE, 20000, SET, 15000, 15000",
		"SALE_PRICE, 20000, ADD, -500, 19500",
		"SALE_PRICE, 20000, PERCENT, -10, 18000",
		"COST_PRICE, 0.10, ADD, 0.20, 0.3",
		"MARGIN_RATE, 15, ADD, 2, 17",
		"COUPON_RATE, 20, ADD, -5, 15",
		"STOCK, 300, PERCENT, -50, 150",
		"WEIGHT, 0.50, PERCENT, 20, 0.6",
		"WEIGHT, 0.05, PERCENT, 10, 0.055",
		"WEIGHT, 1, SET, 0.12501, 0.12501",
		"BUNDLE_QUANTITY, 2, SET, 3, 3",
		"CAPACITY, 12.50, PERCENT, 20, 15"
	})
	void exactArithmetic(ProductNumericField field, String before, NumericChange.Operation operation, String value,
		String after) {
		var result = calculate(field, before, operation, value, NumericChange.FractionPolicy.REJECT);
		assertThat(result.status()).isEqualTo(NumericChangeCalculator.Status.VALID);
		assertThat(result.after()).isEqualTo(after);
		assertThat(result.rounded()).isFalse();
	}

	@ParameterizedTest
	@CsvSource({
		"SALE_PRICE, 101, 50, 151.5, 200",
		"STOCK, 3, 50, 4.5, 4",
		"BUNDLE_QUANTITY, 3, 50, 4.5, 4",
		"BUNDLE_QUANTITY, 3, -50, 1.5, 1"
	})
	void percentageFractionPolicies(ProductNumericField field, String before, String percentage, String raw,
		String rounded) {
		var rejected = calculate(field, before, NumericChange.Operation.PERCENT, percentage,
			NumericChange.FractionPolicy.REJECT);
		assertThat(rejected.status()).isEqualTo(NumericChangeCalculator.Status.INVALID);
		assertThat(rejected.calculated()).isEqualTo(raw);
		assertThat(rejected.after()).isNull();
		var accepted = calculate(field, before, NumericChange.Operation.PERCENT, percentage,
			NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(accepted.after()).isEqualTo(rounded);
		assertThat(accepted.rounded()).isTrue();
	}

	@ParameterizedTest
	@CsvSource({
		"SALE_PRICE, 100, ADD, -101",
		"STOCK, 10, SET, 2147483648",
		"BUNDLE_QUANTITY, 2, SET, 0",
		"SALE_PRICE, 100, SET, 1000000000000000",
		"MARGIN_RATE, 10, SET, 1000",
		"STOCK, 10, SET, 1.5",
		"STOCK, 10, ADD, 0.5",
		"BUNDLE_QUANTITY, 3, SET, 4.5",
		"BUNDLE_QUANTITY, 3, ADD, 0.5",
		"BUNDLE_QUANTITY, 1, PERCENT, -50",
		"STOCK, 3, PERCENT, -101",
		"WEIGHT, 1, SET, 1.000001",
		"WEIGHT, 0.00001, PERCENT, 50",
		"CAPACITY, 1.01, PERCENT, 50",
		"COST_PRICE, 1.01, PERCENT, 50"
	})
	void invalidResultsAreNotRoundedOrClamped(ProductNumericField field, String before,
		NumericChange.Operation operation, String value) {
		var result = calculate(field, before, operation, value, NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(result.status()).isEqualTo(NumericChangeCalculator.Status.INVALID);
		assertThat(result.after()).isNull();
		assertThat(result.reason()).isNotBlank();
	}

	@ParameterizedTest
	@CsvSource({
		"SET, 10000, 12345, 12345, 12300",
		"SET, 10000, 12650, 12650, 12700",
		"SET, 10000, 12349.5, 12349.5, 12300",
		"SET, 10000, 12350, 12350, 12400",
		"ADD, 12000, 345, 12345, 12300",
		"ADD, 13000, -350, 12650, 12700",
		"PERCENT, 10000, 23.45, 12345, 12300",
		"PERCENT, 10000, 26.5, 12650, 12700"
	})
	void salePriceRoundsToNearestHundredForEveryOperation(NumericChange.Operation operation, String before,
		String value, String raw, String after) {
		var result = calculate(ProductNumericField.SALE_PRICE, before, operation, value,
			NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(result.status()).isEqualTo(NumericChangeCalculator.Status.VALID);
		assertThat(result.calculated()).isEqualTo(raw);
		assertThat(result.after()).isEqualTo(after);
		assertThat(result.rounded()).isTrue();
		assertThat(result.reason()).isEqualTo("100원 단위 반올림");
	}

	@Test
	void unchangedAndAlignedPricesAreNotRaisedByAnotherHundred() {
		var aligned = calculate(ProductNumericField.SALE_PRICE, "12300", NumericChange.Operation.SET, "12300",
			NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(aligned.status()).isEqualTo(NumericChangeCalculator.Status.UNCHANGED);
		assertThat(aligned.after()).isEqualTo("12300");
		assertThat(aligned.rounded()).isFalse();
		var roundedBack = calculate(ProductNumericField.SALE_PRICE, "12300", NumericChange.Operation.ADD, "45",
			NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(roundedBack.status()).isEqualTo(NumericChangeCalculator.Status.UNCHANGED);
		assertThat(roundedBack.calculated()).isEqualTo("12345");
		assertThat(roundedBack.rounded()).isTrue();
	}

	@Test
	void priceRoundingCannotOverflowDatabaseRange() {
		var result = calculate(ProductNumericField.SALE_PRICE, "100", NumericChange.Operation.SET, "999999999999999",
			NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(result.status()).isEqualTo(NumericChangeCalculator.Status.INVALID);
		assertThat(result.calculated()).isEqualTo("999999999999999");
		assertThat(result.after()).isNull();
		assertThat(result.reason()).contains("1000000000000000", "허용 범위");
	}

	@Test
	void quantityCanReachZeroForStockButNotForBundle() {
		var result = calculate(ProductNumericField.STOCK, "1", NumericChange.Operation.PERCENT, "-50",
			NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		assertThat(result.status()).isEqualTo(NumericChangeCalculator.Status.VALID);
		assertThat(result.calculated()).isEqualTo("0.5");
		assertThat(result.after()).isEqualTo("0");
		assertThat(result.reason()).contains("버림");
	}

	@Test
	void missingBaseIsNotZeroButCanBeExplicitlySet() {
		for (var operation : new NumericChange.Operation[] {NumericChange.Operation.ADD,
			NumericChange.Operation.PERCENT}) {
			assertThat(
				calculate(ProductNumericField.SALE_PRICE, null, operation, "10", NumericChange.FractionPolicy.REJECT)
					.status())
				.isEqualTo(NumericChangeCalculator.Status.INVALID);
		}
		assertThat(calculate(ProductNumericField.SALE_PRICE, null, NumericChange.Operation.SET, "100",
			NumericChange.FractionPolicy.REJECT).after())
			.isEqualTo("100");
	}

	@Test
	void decimalScaleDoesNotCreateFalseChange() {
		assertThat(calculate(ProductNumericField.WEIGHT, "1.00", NumericChange.Operation.SET, "1",
			NumericChange.FractionPolicy.REJECT).status())
			.isEqualTo(NumericChangeCalculator.Status.UNCHANGED);
	}

	@Test
	void rejectsUnsupportedRateOperationAndExtremeExponent() {
		assertThatThrownBy(
			() -> new NumericChange(ProductNumericField.MARGIN_RATE, NumericChange.Operation.PERCENT, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.SET,
			new BigDecimal("1e100000")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private NumericChangeCalculator.Result calculate(ProductNumericField field, String before,
		NumericChange.Operation operation,
		String value, NumericChange.FractionPolicy policy) {
		return NumericChangeCalculator.calculate(before == null ? null : new BigDecimal(before),
			new NumericChange(field, operation, new BigDecimal(value)), policy);
	}
}

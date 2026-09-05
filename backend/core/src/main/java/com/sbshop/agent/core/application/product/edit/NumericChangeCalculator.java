package com.sbshop.agent.core.application.product.edit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;

public final class NumericChangeCalculator {
	private NumericChangeCalculator() {}

	public enum Status {
		VALID, UNCHANGED, INVALID
	}
	public record Result(ProductNumericField field, String before, String calculated, String after,
		boolean rounded, Status status, String reason) {
	}

	public static Result calculate(BigDecimal before, NumericChange change, NumericChange.FractionPolicy policy) {
		var field = change.field();
		if (before == null && change.operation() != NumericChange.Operation.SET) {
			return invalid(field, before, null, "현재 값이 없어 증감·비율을 계산할 수 없습니다. 값 지정을 사용하세요.");
		}
		BigDecimal calculated = switch (change.operation()) {
			case SET -> change.value();
			case ADD -> before.add(change.value());
			case PERCENT -> before.multiply(BigDecimal.ONE.add(change.value().movePointLeft(2)));
		};
		if (calculated.compareTo(field.minimum()) < 0 || calculated.compareTo(field.maximum()) > 0) {
			return invalid(field, before, calculated, "계산 결과가 허용 범위(" + text(field.minimum())
				+ " ~ " + text(field.maximum()) + ")를 벗어납니다.");
		}
		boolean salePrice = field == ProductNumericField.SALE_PRICE;
		boolean quantityPercentage = field.integerQuantity() && change.operation() == NumericChange.Operation.PERCENT;
		BigDecimal after;
		String roundingReason;
		if (salePrice) {
			// 원 단위로 먼저 반올림하면 12349.5가 12400이 되는 이중 반올림이 발생한다.
			after = SalePriceRounding.nearestHundred(calculated);
			roundingReason = "100원 단위 반올림";
		} else if (quantityPercentage) {
			after = calculated.setScale(0, RoundingMode.DOWN);
			roundingReason = "소수 부분 버림 (정수 수량)";
		} else {
			if (calculated.stripTrailingZeros().scale() > field.scale()) {
				return invalid(field, before, calculated, "허용 소수 자릿수(" + field.scale() + ")를 넘습니다. 정확한 값을 입력하세요.");
			}
			after = calculated;
			roundingReason = null;
		}
		boolean rounded = calculated.compareTo(after) != 0;
		if (rounded && policy == NumericChange.FractionPolicy.REJECT) {
			return invalid(field, before, calculated, roundingReason + "이 필요합니다. 필드별 처리 규칙을 적용하세요.");
		}
		if (after.compareTo(field.minimum()) < 0 || after.compareTo(field.maximum()) > 0) {
			return invalid(field, before, calculated, roundingReason + " 후 값(" + text(after) + ")이 허용 범위를 벗어납니다.");
		}
		Status status = before != null && before.compareTo(after) == 0 ? Status.UNCHANGED : Status.VALID;
		return new Result(field, text(before), text(calculated), text(after), rounded, status,
			rounded ? roundingReason : null);
	}

	private static Result invalid(ProductNumericField field, BigDecimal before, BigDecimal calculated, String reason) {
		return new Result(field, text(before), text(calculated), null, false, Status.INVALID, reason);
	}

	private static String text(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}
}

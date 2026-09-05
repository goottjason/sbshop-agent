package com.sbshop.agent.core.application.product.edit;

import java.math.BigDecimal;

public record NumericChange(ProductNumericField field, Operation operation, BigDecimal value) {
	public enum Operation {
		SET, ADD, PERCENT
	}
	/** 판매가는 모든 연산 후 100원 반올림, 수량은 비율 연산 후 버림. 다른 필드는 정밀도를 보존한다. */
	public enum FractionPolicy {
		REJECT, APPLY_FIELD_RULES
	}

	public NumericChange {
		if (field == null || operation == null || value == null) {
			throw new IllegalArgumentException("필드, 변경 방식, 값은 필수입니다.");
		}
		if (!field.operations().contains(operation)) {
			throw new IllegalArgumentException(field.label() + "에서 지원하지 않는 변경 방식입니다.");
		}
		if (value.precision() > 30 || Math.abs((long)value.scale()) > 30) {
			throw new IllegalArgumentException("변경값의 숫자 범위 또는 소수 자릿수가 너무 큽니다.");
		}
	}
}

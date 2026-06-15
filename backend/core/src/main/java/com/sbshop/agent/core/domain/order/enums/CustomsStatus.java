package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CustomsStatus implements EnumMapperType {
	PENDING("대기중"),
	VALID("일치"),
	VALID_PHONE_MISMATCH("일치"),
	INVALID("불일치");

	private final String label;

	@Override
	public String getName() {
		return name();
	}
}

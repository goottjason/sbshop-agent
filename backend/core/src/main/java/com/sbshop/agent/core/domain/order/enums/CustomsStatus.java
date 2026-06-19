package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CustomsStatus implements EnumMapperType {
	PENDING("대기중"),
	VALID("정상"),
	INVALID_PCCC("통관번호 불일치"),
	INVALID_PHONE("전화번호 불일치"),
	INVALID_ZIPCODE("우편번호 불일치");

	private final String label;

	@Override
	public String getName() {
		return name();
	}
}

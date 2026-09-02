package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 클레임이 어디까지 갔는가. {@link ClaimType} 과 조합해 "반품 요청" 처럼 읽는다.
 *
 * <p>{@code REJECTED} 는 거부·철회로 클레임이 무산된 상태다 — 없던 일이 되므로
 * 주문은 배송 단계로 돌아간다.
 */
@Getter
@RequiredArgsConstructor
public enum ClaimStage implements EnumMapperType {
	NONE("없음"),
	REQUESTED("요청"),
	IN_PROGRESS("처리중"),
	DONE("완료"),
	REJECTED("거부");

	private final String label;

	public boolean isActive() {
		return this == REQUESTED || this == IN_PROGRESS || this == DONE;
	}

	@Override
	public String getName() {
		return name();
	}
}

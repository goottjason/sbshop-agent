package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 클레임의 종류. 배송 단계({@link ShippingStatus})와 독립된 축이다.
 *
 * <p>교환은 종착역이 아니라 경유지다 — 배송중 → 교환요청 → 재발송 → 배송중으로 돌아온다.
 * 그래서 클레임이 배송 단계를 덮어쓰면 안 된다.
 */
@Getter
@RequiredArgsConstructor
public enum ClaimType implements EnumMapperType {
	NONE("없음"),
	CANCEL("취소"),
	RETURN("반품"),
	EXCHANGE("교환");

	private final String label;

	public boolean isActive() {
		return this != NONE;
	}

	/**
	 * 대금이 돌아가는 클레임인가. 교환은 결제가 유지되므로 제외한다.
	 */
	public boolean isRefundTerminalAt(ClaimStage stage) {
		return (this == CANCEL || this == RETURN) && stage == ClaimStage.DONE;
	}

	@Override
	public String getName() {
		return name();
	}
}

package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShippingStatus implements EnumMapperType {
	UNKNOWN("알수없음", -2),
	NEW("결제완료", 0),
	PREPARING("구매준비", 1),
	PURCHASED("구매완료", 2),
	SHIPPED("배송중", 3),
	DELIVERED("배송완료", 4),
	CANCELED("취소됨", -1),
	RETURNED("반품됨", -1),
	EXCHANGED("교환됨", -1);

	private final String label;
	private final int order;

	@Override
	public String getName() {
		return name();
	}

	public static boolean isDowngrade(ShippingStatus current, ShippingStatus next) {
		if (current == null || next == null)
			return false;
		// 터미널 상태(취소/반품/교환)는 항상 허용
		if (next.order < 0)
			return false;
		// 현재 상태가 터미널이면 다운그레이드 아님 (새로운 상태로 전환)
		if (current.order < 0)
			return false;
		return next.order < current.order;
	}
}

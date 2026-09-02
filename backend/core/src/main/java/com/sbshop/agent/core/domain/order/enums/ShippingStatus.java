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
	DISPATCHED("배송지시", 2),
	SHIPPED("배송중", 3),
	DELIVERED("배송완료", 4),
	CONFIRMED("구매확정", 5),

	/** @deprecated D-270 — 클레임은 {@code ClaimData} 로 옮겼다. 새로 쓰지 않는다. */
	@Deprecated
	CANCELED("취소됨", -1),
	/** @deprecated D-270 — {@code ClaimData} 로 옮겼다. */
	@Deprecated
	RETURNED("반품됨", -1),
	/** @deprecated D-270 — {@code ClaimData} 로 옮겼다. */
	@Deprecated
	EXCHANGED("교환됨", -1);

	private final String label;
	private final int order;

	public boolean isRefundTerminal() {
		return this == CANCELED || this == RETURNED;
	}

	@Override
	public String getName() {
		return name();
	}
}

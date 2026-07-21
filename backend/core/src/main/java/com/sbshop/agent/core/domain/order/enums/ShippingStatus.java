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
	CANCELED("취소됨", -1),
	RETURNED("반품됨", -1),
	EXCHANGED("교환됨", -1);

	private final String label;
	private final int order;

	/**
	 * 환불성 종결 상태 여부(취소·반품). 이 상태의 lineItem은 정산액이 0이어야 한다(D-098).
	 * 교환(EXCHANGED)은 결제가 유지되므로 환불성 종결이 아니다.
	 */
	public boolean isRefundTerminal() {
		return this == CANCELED || this == RETURNED;
	}

	@Override
	public String getName() {
		return name();
	}
}

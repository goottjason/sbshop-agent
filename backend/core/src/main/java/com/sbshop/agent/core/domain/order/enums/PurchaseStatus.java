package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PurchaseStatus implements EnumMapperType {
	NOT_PURCHASED("미구매"),
	PURCHASED("구매완료"),
	WAITING_STOCK("입고대기");

	private final String label;

	@Override
	public String getName() {
		return name();
	}
}

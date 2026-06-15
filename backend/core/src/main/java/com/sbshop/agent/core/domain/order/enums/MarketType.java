package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MarketType implements EnumMapperType {
	COUPANG("쿠팡"),
	SMART_STORE("스마트스토어"),
	ELEVEN_STREET("11번가"),
	GMARKET("G마켓"),
	AUCTION("옥션"),
	CAFE24("카페24"),
	UNKNOWN("알 수 없음");

	private final String label;

	@Override
	public String getName() {
		return name();
	}
}

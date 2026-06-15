package com.sbshop.agent.core.domain.product.enums;

import lombok.Getter;

@Getter
public enum MeasureUnit {
	// 1. 수량 단위
	EA("개"),
	CAPSULE("캡슐"),
	TABLET("정(타블렛)"),
	PIECE("조각"),
	PACK("팩"),
	BOX("박스"),
	BOTTLE("병"),
	T_BAG("티백"),
	COUNT("개"), // Added to support existing COUNT in sbshop-agent

	// 2. 무게 단위
	MG("밀리그램"),
	G("그램"),
	KG("킬로그램"),
	OZ("온스"),
	LB("파운드"),

	// 3. 부피 단위
	ML("밀리리터"),
	L("리터"),

	UNKNOWN("기타/미지정");

	private final String description;

	MeasureUnit(String description) {
		this.description = description;
	}
}

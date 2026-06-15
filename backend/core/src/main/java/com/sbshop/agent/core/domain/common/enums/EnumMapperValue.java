package com.sbshop.agent.core.domain.common.enums;

import lombok.Getter;

@Getter
public class EnumMapperValue {
	private String name;
	private String label;

	public EnumMapperValue(EnumMapperType enumMapperType) {
		this.name = enumMapperType.getName();
		this.label = enumMapperType.getLabel();
	}
}

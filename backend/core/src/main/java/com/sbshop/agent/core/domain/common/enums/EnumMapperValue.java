package com.sbshop.agent.core.domain.common.enums;

public record EnumMapperValue(String name, String label) {

	public EnumMapperValue(EnumMapperType enumMapperType) {
		this(enumMapperType.getName(), enumMapperType.getLabel());
	}
}

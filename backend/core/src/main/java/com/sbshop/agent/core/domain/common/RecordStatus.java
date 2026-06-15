package com.sbshop.agent.core.domain.common;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RecordStatus implements EnumMapperType {
	ACTIVE("활성"),
	ARCHIVED("보관"),
	DELETED("삭제");

	private final String label;

	@Override
	public String getName() {
		return name();
	}
}

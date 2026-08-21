package com.sbshop.agent.core.application.sourcing.dto;

import java.util.List;

public record GeneratedProductText(
	String baseName,
	List<String> keywords,
	String categoryHint,
	String generatedBy) {
}

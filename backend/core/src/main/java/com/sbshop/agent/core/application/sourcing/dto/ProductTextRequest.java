package com.sbshop.agent.core.application.sourcing.dto;

public record ProductTextRequest(
	String originalNameKo,
	String brand,
	String brandKo,
	String rootCategory,
	String ingredientsSummary,
	Integer packageQuantity,
	String measureUnitDesc) {
}

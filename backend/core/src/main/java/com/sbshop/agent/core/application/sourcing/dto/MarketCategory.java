package com.sbshop.agent.core.application.sourcing.dto;

public record MarketCategory(String categoryId, String categoryPath, boolean confident) {
	public static MarketCategory unresolved() {
		return new MarketCategory(null, null, false);
	}

	public boolean isResolved() {
		return categoryId != null && !categoryId.isBlank();
	}
}

package com.sbshop.agent.core.application.sourcing.dto;

import java.math.BigDecimal;
import java.util.List;

public record ShoppingStats(
	String query,
	int totalCount,
	BigDecimal lowestPrice,
	BigDecimal medianPrice,
	List<String> topCategories) {
}

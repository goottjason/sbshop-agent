package com.sbshop.agent.core.domain.market.client.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Exact request frozen before user approval; payload contains seller settings and is not a public DTO. */
public record PreparedMarketPublication(String payload, String account, String name, String categoryId,
	String categoryPath, BigDecimal price, int quantity, String representativeImage,
	Map<String, String> shippingSummary) {
	public PreparedMarketPublication {
		shippingSummary = shippingSummary == null ? Map.of() : Map.copyOf(shippingSummary);
	}

	public PreparedMarketPublication(String payload, String account, String name, String categoryId,
		String categoryPath,
		BigDecimal price, int quantity, String representativeImage) {
		this(payload, account, name, categoryId, categoryPath, price, quantity, representativeImage, Map.of());
	}
}

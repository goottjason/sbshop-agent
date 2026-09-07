package com.sbshop.agent.core.domain.market.client.dto;

import java.math.BigDecimal;

/** Exact request frozen before user approval; payload contains seller settings and is not a public DTO. */
public record PreparedMarketPublication(String payload, String account, String name, String categoryId,
	String categoryPath, BigDecimal price, int quantity, String representativeImage) {
}

package com.sbshop.agent.api.dto.product;

import java.math.BigDecimal;

public record PriceStockUpdateRequest(
		BigDecimal price,
		Integer stock) {
}

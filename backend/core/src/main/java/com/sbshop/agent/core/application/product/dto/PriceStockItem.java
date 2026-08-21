package com.sbshop.agent.core.application.product.dto;

import java.math.BigDecimal;

public record PriceStockItem(Long productId, BigDecimal price, Integer stock) {
}

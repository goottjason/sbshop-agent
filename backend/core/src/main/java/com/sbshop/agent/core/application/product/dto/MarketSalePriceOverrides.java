package com.sbshop.agent.core.application.product.dto;

import java.math.BigDecimal;

public record MarketSalePriceOverrides(
	BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
	public static final MarketSalePriceOverrides EMPTY = new MarketSalePriceOverrides(null, null, null);
}

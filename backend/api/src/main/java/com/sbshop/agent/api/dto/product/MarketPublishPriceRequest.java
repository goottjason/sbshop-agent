package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import java.math.BigDecimal;

public record MarketPublishPriceRequest(
	BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {

	public MarketSalePriceOverrides toOverrides() {
		return new MarketSalePriceOverrides(marginRate, couponRate, minMarginPrice);
	}
}

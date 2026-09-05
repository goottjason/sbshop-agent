package com.sbshop.agent.core.application.product.dto;

import java.math.BigDecimal;

public record PricingInputs(
	BigDecimal buyPrice, int bundleQty, BigDecimal marginRate, BigDecimal couponRate,
	BigDecimal minMarginPrice, BigDecimal domesticFee, BigDecimal domesticFreeOver) {
	public PricingInputs(BigDecimal buyPrice, int bundleQty, BigDecimal marginRate, BigDecimal couponRate,
		BigDecimal minMarginPrice) {
		this(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice, null, null);
	}
}

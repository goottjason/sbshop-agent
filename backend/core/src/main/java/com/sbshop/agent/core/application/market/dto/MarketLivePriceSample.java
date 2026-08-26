package com.sbshop.agent.core.application.market.dto;

import java.math.BigDecimal;

public record MarketLivePriceSample(
	String sbCode,
	Long productId,
	String sellerProductId,
	String optionId,
	MarketLiveStatus status,
	BigDecimal localPolicyPrice,
	Integer draftSalePrice,
	Integer liveSalePrice,
	Integer liveStock,
	String note) {
}

package com.sbshop.agent.core.domain.market.client.dto;

public record MarketDraftPrice(Integer salePrice, MarketDraftPriceMiss miss) {

	public static MarketDraftPrice of(int salePrice) {
		return new MarketDraftPrice(salePrice, null);
	}

	public static MarketDraftPrice missing(MarketDraftPriceMiss miss) {
		return new MarketDraftPrice(null, miss);
	}

	public boolean isPresent() {
		return salePrice != null;
	}
}

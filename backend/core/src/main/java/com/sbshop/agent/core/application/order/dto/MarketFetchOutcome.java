package com.sbshop.agent.core.application.order.dto;

import java.util.List;

public record MarketFetchOutcome(List<MarketOrderDto> orders, boolean complete) {
	public static MarketFetchOutcome complete(List<MarketOrderDto> orders) {
		return new MarketFetchOutcome(orders, true);
	}

	public static MarketFetchOutcome partial(List<MarketOrderDto> orders) {
		return new MarketFetchOutcome(orders, false);
	}
}

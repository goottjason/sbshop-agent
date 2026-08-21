package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import java.util.Map;

public record MarketPublishResponse(String market, String status, String url, Map<String, String> identifiers) {

	public static MarketPublishResponse from(MarketPublishOutcome outcome, String url) {
		return new MarketPublishResponse(
			outcome.marketType().name(),
			outcome.synced() ? MarketBadgeState.SYNCED : MarketBadgeState.PENDING,
			(url == null || url.isBlank()) ? null : url,
			outcome.identifiers());
	}
}

package com.sbshop.agent.api.dto.product;

public record MarketBadgeState(String status, String url) {

	public static final String SYNCED = "SYNCED";
	public static final String PENDING = "PENDING";

	public static MarketBadgeState of(boolean synced, String url) {
		return new MarketBadgeState(synced ? SYNCED : PENDING, (url == null || url.isBlank()) ? null : url);
	}
}

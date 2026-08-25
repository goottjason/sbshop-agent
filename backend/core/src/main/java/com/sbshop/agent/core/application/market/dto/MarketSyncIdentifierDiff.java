package com.sbshop.agent.core.application.market.dto;

public record MarketSyncIdentifierDiff(
	String key,
	String localValue,
	String marketValue) {
}

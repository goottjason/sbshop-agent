package com.sbshop.agent.core.application.market.dto;

import java.util.List;
import java.util.Map;

public record MarketSyncSample(
	String sbCode,
	Long productId,
	String matchedBy,
	String marketStatus,
	Map<String, String> localIdentifiers,
	Map<String, String> marketIdentifiers,
	List<MarketSyncIdentifierDiff> differences,
	String note) {
}

package com.sbshop.agent.core.application.market.dto;

import java.util.List;
import java.util.Map;

public record MarketSyncMarketReport(
	String market,
	String marketLabel,
	MarketSyncOutcome outcome,
	String failureReason,
	int localTotal,
	int localWithSbCode,
	int localWithoutIdentifiers,
	int marketTotal,
	int marketWithSellerCode,
	int matchedBySbCode,
	int matchedByIdentifier,
	Map<MarketSyncBucket, Integer> bucketCounts,
	Map<String, Integer> marketStatusCounts,
	Map<MarketSyncBucket, List<MarketSyncSample>> samples,
	int deepLookups,
	boolean deepTruncated,
	int persistedAbsent,
	long elapsedMs,
	List<String> warnings,
	MarketLiveInventoryReport liveInventory) {
}

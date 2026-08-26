package com.sbshop.agent.core.application.market.dto;

import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPriceMiss;
import java.util.List;
import java.util.Map;

public record MarketLiveInventoryReport(
	int candidates,
	int examined,
	boolean truncated,
	Map<MarketLiveStatus, Integer> statusCounts,
	int noOptionId,
	int lookupFailed,
	int optionAbsent,
	int priceComparable,
	int priceAllEqual,
	int priceDiverged,
	int localVsLiveDiverged,
	int draftVsLiveDiverged,
	int draftAboveLive,
	int draftBelowLive,
	int draftUnknown,
	Map<MarketDraftPriceMiss, Integer> draftMissReasons,
	boolean draftAboveLiveUnderstated,
	boolean draftMeasurementUnreliable,
	int localPriceUnknown,
	long elapsedMs,
	List<MarketLivePriceSample> samples,
	List<String> warnings) {
}

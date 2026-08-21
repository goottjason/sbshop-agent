package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;

public record MarketRepublishResult(
	List<MarketType> synced,
	List<MarketType> skipped,
	Map<MarketType, String> failed) {
}

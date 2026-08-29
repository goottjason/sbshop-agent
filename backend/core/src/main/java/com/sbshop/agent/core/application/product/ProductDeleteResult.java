package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;

public record ProductDeleteResult(
	List<MarketType> deleted,
	List<MarketType> skipped,
	Map<MarketType, String> failed,
	Map<MarketType, String> manual,
	boolean disposed) {

	public ProductDeleteResult(List<MarketType> deleted, List<MarketType> skipped,
		Map<MarketType, String> failed) {
		this(deleted, skipped, failed, Map.of(), true);
	}
}

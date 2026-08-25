package com.sbshop.agent.core.application.market.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MarketSyncReport(
	LocalDateTime generatedAt,
	int sampleLimit,
	boolean deep,
	long elapsedMs,
	List<MarketSyncMarketReport> markets) {
}

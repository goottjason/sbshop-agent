package com.sbshop.agent.core.application.sourcing.dto;

import java.time.LocalDateTime;
import java.util.List;

public record DiscoverySummary(
	LocalDateTime startedAt,
	LocalDateTime finishedAt,
	int crawled,
	int created,
	int updated,
	int excluded,
	int scored,
	int customsBlocked,
	int customsReview,
	int cooldownReleased,
	List<String> warnings) {
	public static DiscoverySummary failed(LocalDateTime startedAt, List<String> warnings) {
		return new DiscoverySummary(startedAt, LocalDateTime.now(),
			0, 0, 0, 0, 0, 0, 0, 0, warnings);
	}
}

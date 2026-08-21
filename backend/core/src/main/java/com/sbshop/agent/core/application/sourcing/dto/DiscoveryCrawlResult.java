package com.sbshop.agent.core.application.sourcing.dto;

import java.util.List;

public record DiscoveryCrawlResult(
	List<DiscoveredCandidateDto> candidates,
	List<String> failures) {
	public static DiscoveryCrawlResult empty(String failure) {
		return new DiscoveryCrawlResult(List.of(), List.of(failure));
	}
}

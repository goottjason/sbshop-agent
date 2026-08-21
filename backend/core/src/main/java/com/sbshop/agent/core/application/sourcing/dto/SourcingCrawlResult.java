package com.sbshop.agent.core.application.sourcing.dto;

import java.util.List;

public record SourcingCrawlResult(
	List<ScrapedProductDto> succeeded,
	List<SourcingFailure> failed) {
	public record SourcingFailure(String url, String reason) {
	}
}

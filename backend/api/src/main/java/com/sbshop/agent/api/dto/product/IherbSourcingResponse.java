package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import java.util.List;

public record IherbSourcingResponse(
	List<ProductSourcingResponse> succeeded,
	List<Failure> failed) {

	public record Failure(String url, String reason) {
	}

	public static IherbSourcingResponse from(SourcingCrawlResult result) {
		List<ProductSourcingResponse> succeeded = result.succeeded().stream()
			.map(ProductSourcingResponse::from)
			.toList();
		List<Failure> failed = result.failed().stream()
			.map(f -> new Failure(f.url(), f.reason()))
			.toList();
		return new IherbSourcingResponse(succeeded, failed);
	}
}

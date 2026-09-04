package com.sbshop.agent.core.application.product.port;

import java.util.List;

public record BrandLookupOutcome(Status status, String officialBrandName, List<String> candidates) {

	public enum Status {
		MATCHED,
		NOT_REGISTERED,
		LOOKUP_FAILED
	}

	public static BrandLookupOutcome matched(String officialBrandName) {
		return new BrandLookupOutcome(Status.MATCHED, officialBrandName, List.of(officialBrandName));
	}

	public static BrandLookupOutcome matched(String officialBrandName, List<String> candidates) {
		return new BrandLookupOutcome(Status.MATCHED, officialBrandName, candidates);
	}

	public static BrandLookupOutcome notRegistered() {
		return new BrandLookupOutcome(Status.NOT_REGISTERED, null, List.of());
	}

	public static BrandLookupOutcome notRegistered(List<String> candidates) {
		return new BrandLookupOutcome(Status.NOT_REGISTERED, null, candidates);
	}

	public static BrandLookupOutcome lookupFailed() {
		return new BrandLookupOutcome(Status.LOOKUP_FAILED, null, List.of());
	}

	public boolean isMatched() {
		return status == Status.MATCHED;
	}

	public boolean isCacheable() {
		return status != Status.LOOKUP_FAILED;
	}
}

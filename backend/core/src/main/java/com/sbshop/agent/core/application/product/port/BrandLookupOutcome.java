package com.sbshop.agent.core.application.product.port;

public record BrandLookupOutcome(Status status, String officialBrandName) {

	public enum Status {
		MATCHED,
		NOT_REGISTERED,
		LOOKUP_FAILED
	}

	public static BrandLookupOutcome matched(String officialBrandName) {
		return new BrandLookupOutcome(Status.MATCHED, officialBrandName);
	}

	public static BrandLookupOutcome notRegistered() {
		return new BrandLookupOutcome(Status.NOT_REGISTERED, null);
	}

	public static BrandLookupOutcome lookupFailed() {
		return new BrandLookupOutcome(Status.LOOKUP_FAILED, null);
	}

	public boolean isMatched() {
		return status == Status.MATCHED;
	}

	public boolean isCacheable() {
		return status != Status.LOOKUP_FAILED;
	}
}

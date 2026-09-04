package com.sbshop.agent.core.application.product.port;

public record BrandLookupOutcome(Status status, String officialBrandName, String rawResponse) {

	public enum Status {
		MATCHED,
		NOT_REGISTERED,
		LOOKUP_FAILED
	}

	public static BrandLookupOutcome matched(String officialBrandName) {
		return new BrandLookupOutcome(Status.MATCHED, officialBrandName, null);
	}

	public static BrandLookupOutcome notRegistered() {
		return new BrandLookupOutcome(Status.NOT_REGISTERED, null, null);
	}

	public static BrandLookupOutcome notRegistered(String rawResponse) {
		return new BrandLookupOutcome(Status.NOT_REGISTERED, null, rawResponse);
	}

	public static BrandLookupOutcome lookupFailed() {
		return new BrandLookupOutcome(Status.LOOKUP_FAILED, null, null);
	}

	public static BrandLookupOutcome lookupFailed(String rawResponse) {
		return new BrandLookupOutcome(Status.LOOKUP_FAILED, null, rawResponse);
	}

	public boolean isMatched() {
		return status == Status.MATCHED;
	}

	public boolean isCacheable() {
		return status != Status.LOOKUP_FAILED;
	}
}

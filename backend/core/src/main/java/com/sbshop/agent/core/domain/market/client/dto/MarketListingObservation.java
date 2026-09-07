package com.sbshop.agent.core.domain.market.client.dto;

import java.time.Instant;

/** A read result, never a statement that product fields are synchronized. */
public record MarketListingObservation(State state, String code, String detail, String accountReference,
	String endpoint, Instant observedAt, Instant retryAfter) {

	public MarketListingObservation(State state, String code, String detail, String accountReference, String endpoint,
		Instant observedAt) {
		this(state, code, detail, accountReference, endpoint, observedAt, null);
	}

	public boolean retryable() {
		return state == State.UNKNOWN && code != null
			&& (code.startsWith("HTTP_429") || code.startsWith("HTTP_5") || code.equals("TRANSPORT_ERROR"));
	}

	public boolean rateLimited() {
		return code != null && code.startsWith("HTTP_429");
	}
	public enum State {
		PRESENT, OUT_OF_STOCK, STOPPED, PROHIBITED, DELETED, UNKNOWN
	}

	public static MarketListingObservation unknown(String detail) {
		return new MarketListingObservation(State.UNKNOWN, "UNVERIFIED", detail, null, null, Instant.now());
	}
}

package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import java.time.LocalDateTime;

public record MarketBadgeState(String status, String url, String reason, String errorAt, Transmission transmission) {
	public record Transmission(String outcome, String detail, String completedAt, String capturedAt) {
	}

	public MarketBadgeState(String status, String url, String reason, String errorAt) {
		this(status, url, reason, errorAt, null);
	}

	public static MarketBadgeState forConnection(com.sbshop.agent.core.domain.market.MarketConnectionState state,
		MarketBadgeState legacy) {
		if (!state.detached())
			return legacy;
		return new MarketBadgeState(
			state == com.sbshop.agent.core.domain.market.MarketConnectionState.DETACHED_PROHIBITED
				? "PROHIBITED" : "DETACHED",
			legacy.url(), state.name(), null);
	}

	public static final String SYNCED = "SYNCED";
	public static final String PENDING = "PENDING";
	public static final String DELETED = "DELETED";
	public static final String FAILED = "FAILED";
	public static final String UNVERIFIED = "UNVERIFIED";

	public static MarketBadgeState marketPlusUnverified(String url) {
		return new MarketBadgeState(UNVERIFIED, normalize(url), "MARKETPLUS_RESULT_UNVERIFIED", null);
	}

	public static MarketBadgeState marketPlusObserved(String url,
		com.sbshop.agent.core.application.market.marketplus.MarketPlusTransmissionService.Summary observation) {
		if (observation == null)
			return marketPlusUnverified(url);
		boolean failed = "FAILURE".equals(observation.outcome());
		return new MarketBadgeState(failed ? "TRANSFER_FAILED" : UNVERIFIED, normalize(url), observation.reasonCode(),
			failed ? observation.completedAt().toString() : null,
			new Transmission(observation.outcome(), observation.detail(), observation.completedAt().toString(),
				observation.capturedAt().toString()));
	}

	public static MarketBadgeState of(boolean synced, String url) {
		return new MarketBadgeState(synced ? SYNCED : PENDING, normalize(url), null, null);
	}

	public static MarketBadgeState of(boolean hasIdentifiers, boolean isSynced, UnsyncReason reason,
		SyncErrorType syncError, LocalDateTime errorAt, String url) {
		String normalized = normalize(url);
		if (!isSynced && reason == UnsyncReason.DELETED_ON_MARKET) {
			return new MarketBadgeState(DELETED, normalized, reason.name(), null);
		}
		if (syncError != null && hasIdentifiers) {
			return new MarketBadgeState(FAILED, normalized, syncError.name(),
				errorAt != null ? errorAt.toString() : null);
		}
		return new MarketBadgeState(hasIdentifiers ? SYNCED : PENDING, normalized, null, null);
	}

	private static String normalize(String url) {
		return (url == null || url.isBlank()) ? null : url;
	}
}

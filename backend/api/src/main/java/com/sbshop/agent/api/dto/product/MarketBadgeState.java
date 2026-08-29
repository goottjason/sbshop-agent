package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;

public record MarketBadgeState(String status, String url, String reason) {

	public static final String SYNCED = "SYNCED";
	public static final String PENDING = "PENDING";
	public static final String DELETED = "DELETED";
	public static final String FAILED = "FAILED";

	public static MarketBadgeState of(boolean synced, String url) {
		return new MarketBadgeState(synced ? SYNCED : PENDING, normalize(url), null);
	}

	public static MarketBadgeState of(boolean hasIdentifiers, boolean isSynced, UnsyncReason reason,
		SyncErrorType syncError, String url) {
		String normalized = normalize(url);
		if (!isSynced && reason == UnsyncReason.DELETED_ON_MARKET) {
			return new MarketBadgeState(DELETED, normalized, reason.name());
		}
		if (syncError != null && hasIdentifiers) {
			return new MarketBadgeState(FAILED, normalized, syncError.name());
		}
		return new MarketBadgeState(hasIdentifiers ? SYNCED : PENDING, normalized, null);
	}

	private static String normalize(String url) {
		return (url == null || url.isBlank()) ? null : url;
	}
}

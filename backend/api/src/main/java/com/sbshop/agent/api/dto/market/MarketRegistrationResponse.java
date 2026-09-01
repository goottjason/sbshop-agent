package com.sbshop.agent.api.dto.market;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import java.time.LocalDateTime;

public record MarketRegistrationResponse(
	Long id,
	String status,
	LocalDateTime createdAt,
	LocalDateTime updatedAt,
	Long productId,
	Long sbProductId,
	String marketType,
	String marketProductName,
	@JsonRawValue
	String marketIdentifiers,
	@JsonRawValue
	String marketDetailedInfo,
	Boolean isSynced,
	LocalDateTime lastSyncedAt,
	String unsyncReason,
	String lastSyncError,
	String lastSyncErrorMessage) {

	public static MarketRegistrationResponse from(MarketRegistration r) {
		return new MarketRegistrationResponse(
			r.getId(),
			r.getStatus() != null ? r.getStatus().name() : null,
			r.getCreatedAt(),
			r.getUpdatedAt(),
			r.getProductId(),
			r.getSbProductId(),
			r.getMarketType() != null ? r.getMarketType().name() : null,
			r.getMarketProductName(),
			r.getMarketIdentifiers(),
			r.getMarketDetailedInfo(),
			r.getIsSynced(),
			r.getLastSyncedAt(),
			r.getUnsyncReason() != null ? r.getUnsyncReason().name() : null,
			r.getLastSyncError() != null ? r.getLastSyncError().name() : null,
			r.getLastSyncErrorMessage());
	}
}

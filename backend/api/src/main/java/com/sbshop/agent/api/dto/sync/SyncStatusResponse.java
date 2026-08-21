package com.sbshop.agent.api.dto.sync;

import com.sbshop.agent.core.application.sync.SyncStatusService;
import java.time.LocalDateTime;

public record SyncStatusResponse(
	String marketType,
	String status,
	LocalDateTime lastSyncAt,
	String errorMessage) {

	public static SyncStatusResponse from(SyncStatusService.SyncStatus s) {
		return new SyncStatusResponse(
			s.getMarketType(),
			s.getStatus(),
			s.getLastSyncAt(),
			s.getErrorMessage());
	}
}

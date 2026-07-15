package com.sbshop.agent.api.dto.sync;

import com.sbshop.agent.core.application.sync.SyncStatusService;
import java.time.LocalDateTime;

/**
 * F-SYNC-24: 마켓 동기화 상태 응답 DTO.
 *
 * 기존에는 {@code /api/v1/orders/sync/status}가 서비스 내부 정적클래스
 * {@link SyncStatusService.SyncStatus}를 직접 직렬화했다. 프론트 {@code SyncStatus}
 * 인터페이스(marketType·status·lastSyncAt·errorMessage)와 동일 필드로 미러한다.
 */
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

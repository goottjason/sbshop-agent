package com.sbshop.agent.core.domain.sync;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 마켓별 주문 동기화 실행 상태(SP-2, F-SYNC-1).
 *
 * api·worker는 서로 다른 JVM이라 인메모리(ConcurrentHashMap)로는 상태를 공유할 수 없다.
 * 스케줄러(worker)가 기록하고 컨트롤러(api)가 조회하려면 DB가 단일 원본이어야 한다.
 * BaseEntity에 이미 status(RecordStatus)가 있으므로 동기화 상태 필드는 syncStatus(컬럼 sync_status)로 둔다.
 */
@Entity
@Table(name = "sb_market_sync_status", uniqueConstraints = @UniqueConstraint(columnNames = "market_type"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketSyncStatus extends BaseEntity {

	@Column(name = "market_type", nullable = false, length = 30)
	private String marketType;

	@Column(name = "sync_status", nullable = false, length = 20)
	private String syncStatus;

	@Column(name = "last_sync_at")
	private LocalDateTime lastSyncAt;

	@Column(name = "error_message", columnDefinition = "text")
	private String errorMessage;

	@Builder
	public MarketSyncStatus(String marketType, String syncStatus, LocalDateTime lastSyncAt,
		String errorMessage) {
		this.marketType = marketType;
		this.syncStatus = syncStatus;
		this.lastSyncAt = lastSyncAt;
		this.errorMessage = errorMessage;
	}

	/** RUNNING 진입: 이전 에러 메시지를 지우고 상태만 갱신(완료 시점은 아직 없음). */
	public void markRunning() {
		this.syncStatus = "RUNNING";
		this.errorMessage = null;
	}

	/** 정상 완료: 완료 시각 기록, 에러 메시지 클리어. */
	public void markCompleted(LocalDateTime completedAt) {
		this.syncStatus = "COMPLETED";
		this.lastSyncAt = completedAt;
		this.errorMessage = null;
	}

	/** 실패: 실패 시각·사유 기록. */
	public void markFailed(LocalDateTime failedAt, String errorMessage) {
		this.syncStatus = "FAILED";
		this.lastSyncAt = failedAt;
		this.errorMessage = errorMessage;
	}
}

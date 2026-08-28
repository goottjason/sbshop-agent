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

	@Column(name = "processed_count")
	private Integer processedCount;

	@Column(name = "new_count")
	private Integer newCount;

	@Column(name = "last_new_at")
	private LocalDateTime lastNewAt;

	@Builder
	public MarketSyncStatus(String marketType, String syncStatus, LocalDateTime lastSyncAt,
		String errorMessage) {
		this.marketType = marketType;
		this.syncStatus = syncStatus;
		this.lastSyncAt = lastSyncAt;
		this.errorMessage = errorMessage;
	}

	public void markRunning() {
		this.syncStatus = "RUNNING";
		this.errorMessage = null;
	}

	public void markCompleted(LocalDateTime completedAt) {
		this.syncStatus = "COMPLETED";
		this.lastSyncAt = completedAt;
		this.errorMessage = null;
	}

	public void markCompleted(LocalDateTime completedAt, int processedCount, int newCount) {
		markCompleted(completedAt);
		this.processedCount = processedCount;
		this.newCount = newCount;
		if (newCount > 0) {
			this.lastNewAt = completedAt;
		}
	}

	public void markFailed(LocalDateTime failedAt, String errorMessage) {
		this.syncStatus = "FAILED";
		this.lastSyncAt = failedAt;
		this.errorMessage = errorMessage;
	}
}

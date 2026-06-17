package com.sbshop.agent.core.application.sync;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SyncStatusService {

	private final ConcurrentHashMap<String, SyncStatus> statuses = new ConcurrentHashMap<>();

	public void markRunning(String marketType) {
		statuses.put(marketType, new SyncStatus(marketType, "RUNNING", null, null));
	}

	public void markCompleted(String marketType) {
		SyncStatus current = statuses.get(marketType);
		statuses.put(marketType, new SyncStatus(marketType, "COMPLETED", LocalDateTime.now(),
			current != null ? current.getErrorMessage() : null));
	}

	public void markFailed(String marketType, String errorMessage) {
		statuses.put(marketType, new SyncStatus(marketType, "FAILED", LocalDateTime.now(), errorMessage));
	}

	public Map<String, SyncStatus> getAllStatuses() {
		return new HashMap<>(statuses);
	}

	@Getter
	@RequiredArgsConstructor
	public static class SyncStatus {
		private final String marketType;
		private final String status;
		private final LocalDateTime lastSyncAt;
		private final String errorMessage;
	}
}

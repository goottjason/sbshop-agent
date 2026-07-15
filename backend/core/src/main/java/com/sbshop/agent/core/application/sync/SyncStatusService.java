package com.sbshop.agent.core.application.sync;

import com.sbshop.agent.core.domain.sync.MarketSyncStatus;
import com.sbshop.agent.core.domain.sync.repository.MarketSyncStatusRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마켓별 주문 동기화 상태 추적 서비스(SP-2, F-SYNC-1).
 *
 * 과거 인메모리(ConcurrentHashMap) 구현은 api·worker가 다른 JVM이라 상태를 공유하지 못했다
 * (worker가 쓰고 api가 읽는데 서로 다른 맵). DB(sb_market_sync_status)를 단일 원본으로 삼아
 * 마켓당 단일 row를 upsert 한다. 공개 API 시그니처와 SyncStatus DTO는 유지(호출부·응답계약 무변경).
 */
@Service
@RequiredArgsConstructor
public class SyncStatusService {

	private final MarketSyncStatusRepository repository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markRunning(String marketType) {
		MarketSyncStatus entity = repository.findByMarketType(marketType)
			.orElseGet(() -> MarketSyncStatus.builder()
				.marketType(marketType)
				.syncStatus("RUNNING")
				.build());
		entity.markRunning();
		repository.save(entity);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markCompleted(String marketType) {
		MarketSyncStatus entity = repository.findByMarketType(marketType)
			.orElseGet(() -> MarketSyncStatus.builder()
				.marketType(marketType)
				.syncStatus("COMPLETED")
				.build());
		entity.markCompleted(LocalDateTime.now());
		repository.save(entity);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(String marketType, String errorMessage) {
		MarketSyncStatus entity = repository.findByMarketType(marketType)
			.orElseGet(() -> MarketSyncStatus.builder()
				.marketType(marketType)
				.syncStatus("FAILED")
				.build());
		entity.markFailed(LocalDateTime.now(), errorMessage);
		repository.save(entity);
	}

	@Transactional(readOnly = true)
	public Map<String, SyncStatus> getAllStatuses() {
		Map<String, SyncStatus> result = new LinkedHashMap<>();
		for (MarketSyncStatus entity : repository.findAll()) {
			result.put(entity.getMarketType(), new SyncStatus(
				entity.getMarketType(),
				entity.getSyncStatus(),
				entity.getLastSyncAt(),
				entity.getErrorMessage()));
		}
		return result;
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

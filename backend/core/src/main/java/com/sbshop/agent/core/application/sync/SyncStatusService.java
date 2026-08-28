package com.sbshop.agent.core.application.sync;

import com.sbshop.agent.core.domain.sync.MarketSyncStatus;
import com.sbshop.agent.core.domain.sync.repository.MarketSyncStatusRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
	public boolean tryMarkRunning(String marketType) {
		if (repository.claimRunning(marketType) == 1) {
			return true;
		}
		if (repository.findByMarketType(marketType).isPresent()) {
			return false;
		}
		try {
			repository.save(MarketSyncStatus.builder()
				.marketType(marketType)
				.syncStatus("RUNNING")
				.build());
			return true;
		} catch (DataIntegrityViolationException e) {
			return false;
		}
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
	public void markCompleted(String marketType, int processedCount, int newCount) {
		MarketSyncStatus entity = repository.findByMarketType(marketType)
			.orElseGet(() -> MarketSyncStatus.builder()
				.marketType(marketType)
				.syncStatus("COMPLETED")
				.build());
		entity.markCompleted(LocalDateTime.now(), processedCount, newCount);
		repository.save(entity);
	}

	@Transactional(readOnly = true)
	public Optional<LocalDateTime> lastNewAt(String marketType) {
		return repository.findByMarketType(marketType).map(MarketSyncStatus::getLastNewAt);
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

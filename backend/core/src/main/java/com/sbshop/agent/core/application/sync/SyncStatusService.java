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

	/**
	 * F-SYNC-17: 원자적 RUNNING 클레임. 이미 RUNNING이면 false(중복 → 스킵), 아니면 RUNNING 세팅 후 true.
	 *
	 * <p>정산 동기화는 워커 스케줄러 + api 수동으로 교차 JVM 트리거 가능하므로 in-JVM AtomicBoolean으로는
	 * 부족하다. DB를 단일 원본으로 삼아 조건부 UPDATE(rows-affected)로 원자성을 확보한다.
	 * H2·PostgreSQL 모두 동작하는 이식성 있는 방식이다.
	 *
	 * <p>순서:
	 * <ol>
	 *   <li>기존 row가 RUNNING이 아니면 조건부 UPDATE로 RUNNING 세팅 → 1건이면 클레임 성공(true).</li>
	 *   <li>0건이면 row가 없거나(insert 시도) 이미 RUNNING(스킵). row 존재 시 RUNNING이면 false.</li>
	 *   <li>row가 없으면 RUNNING으로 insert → true. 동시 double-insert는 market_type 유니크 제약이
	 *       한쪽을 예외로 막으므로, 예외 발생 시 상대가 이미 클레임한 것으로 보고 false.</li>
	 * </ol>
	 * 해제는 기존 markCompleted / markFailed로 수행한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean tryMarkRunning(String marketType) {
		// 1. 기존 non-RUNNING row를 원자적으로 RUNNING으로 전환
		if (repository.claimRunning(marketType) == 1) {
			return true;
		}
		// 2. 갱신된 row가 없다: row가 없거나 이미 RUNNING
		if (repository.findByMarketType(marketType).isPresent()) {
			return false; // 이미 RUNNING → 중복, 스킵
		}
		// 3. row가 없으면 새로 RUNNING insert. 동시 insert 경쟁은 유니크 제약이 한쪽을 막는다.
		try {
			repository.save(MarketSyncStatus.builder()
				.marketType(marketType)
				.syncStatus("RUNNING")
				.build());
			return true;
		} catch (org.springframework.dao.DataIntegrityViolationException e) {
			// 상대 JVM/스레드가 먼저 insert(=클레임)함 → 스킵
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

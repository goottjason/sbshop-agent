package com.sbshop.agent.core.domain.sync.repository;

import com.sbshop.agent.core.domain.sync.MarketSyncStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketSyncStatusRepository extends JpaRepository<MarketSyncStatus, Long> {
	Optional<MarketSyncStatus> findByMarketType(String marketType);

	/**
	 * F-SYNC-17: 원자적 클레임. 이미 RUNNING이 아닌 기존 row만 RUNNING으로 조건부 갱신하고
	 * 영향받은 row 수를 반환한다. H2·PostgreSQL 모두 동작하는 이식성 있는 조건부 UPDATE.
	 * 반환 1 = 클레임 성공, 0 = 이미 RUNNING(스킵) 또는 row 없음(호출부에서 insert 시도).
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE MarketSyncStatus e SET e.syncStatus = 'RUNNING', e.errorMessage = null "
		+ "WHERE e.marketType = :marketType AND e.syncStatus <> 'RUNNING'")
	int claimRunning(@Param("marketType") String marketType);
}

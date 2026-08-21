package com.sbshop.agent.core.domain.sync.repository;

import com.sbshop.agent.core.domain.sync.MarketSyncStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketSyncStatusRepository extends JpaRepository<MarketSyncStatus, Long> {
	Optional<MarketSyncStatus> findByMarketType(String marketType);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE MarketSyncStatus e SET e.syncStatus = 'RUNNING', e.errorMessage = null "
		+ "WHERE e.marketType = :marketType AND e.syncStatus <> 'RUNNING'")
	int claimRunning(@Param("marketType")
	String marketType);
}

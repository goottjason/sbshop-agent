package com.sbshop.agent.core.domain.sync.repository;

import com.sbshop.agent.core.domain.sync.MarketSyncStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketSyncStatusRepository extends JpaRepository<MarketSyncStatus, Long> {
	Optional<MarketSyncStatus> findByMarketType(String marketType);
}

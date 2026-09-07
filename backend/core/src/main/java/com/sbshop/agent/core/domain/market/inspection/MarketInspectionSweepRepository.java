package com.sbshop.agent.core.domain.market.inspection;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketInspectionSweepRepository extends JpaRepository<MarketInspectionSweep, String> {
	Optional<MarketInspectionSweep> findTopByOrderByRunDateDesc();

	boolean existsByRunDate(LocalDate date);

	Optional<MarketInspectionSweep> findTopByMarketOrderByRunDateDesc(String market);

	boolean existsByMarketAndRunDate(String market, LocalDate date);
}

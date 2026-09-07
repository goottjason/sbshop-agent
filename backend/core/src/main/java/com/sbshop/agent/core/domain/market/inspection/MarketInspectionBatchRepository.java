package com.sbshop.agent.core.domain.market.inspection;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketInspectionBatchRepository extends JpaRepository<MarketInspectionBatch, String> {
	List<MarketInspectionBatch> findTop20ByOrderByCreatedAtDesc();

	List<MarketInspectionBatch> findBySweepIdOrderByCreatedAtAscIdAsc(String sweepId);
}

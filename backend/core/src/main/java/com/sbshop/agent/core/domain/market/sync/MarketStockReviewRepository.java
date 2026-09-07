package com.sbshop.agent.core.domain.market.sync;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketStockReviewRepository extends JpaRepository<MarketStockReview, String> {
	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from MarketStockReview r where r.id=:id")
	Optional<MarketStockReview> lock(@Param("id")
	String id);

	List<MarketStockReview> findTop20ByActorOrderByCreatedAtDesc(String actor);
}

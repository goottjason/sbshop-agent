package com.sbshop.agent.core.domain.market.sync;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketPriceReviewRepository extends JpaRepository<MarketPriceReview, String> {
	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from MarketPriceReview r where r.id=:id")
	Optional<MarketPriceReview> lock(@Param("id")
	String id);

	List<MarketPriceReview> findTop20ByOrderByCreatedAtDesc();
}

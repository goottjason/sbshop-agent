package com.sbshop.agent.core.domain.market.sync;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketFieldReviewRepository extends JpaRepository<MarketFieldReview, String> {
	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from MarketFieldReview r where r.id=:id")
	Optional<MarketFieldReview> lock(@Param("id")
	String id);

	List<MarketFieldReview> findTop20ByActorOrderByCreatedAtDesc(String actor);
}

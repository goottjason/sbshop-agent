package com.sbshop.agent.core.domain.market.marketplus;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketPlusPublicCheckRepository extends JpaRepository<MarketPlusPublicCheck, Long> {
	List<MarketPlusPublicCheck> findByCollectionIdOrderById(String id);

	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from MarketPlusPublicCheck t where t.id=:id")
	Optional<MarketPlusPublicCheck> lock(@Param("id")
	Long id);

	@Query("select t from MarketPlusPublicCheck t where t.market in :markets and ((t.state in ('QUEUED','RETRY_WAIT') and t.nextRunAt<=:now) or (t.state='RUNNING' and t.leaseUntil<=:now)) order by t.id")
	List<MarketPlusPublicCheck> due(@Param("now")
	Instant now, @Param("markets")
	List<String> markets, Pageable page);
}

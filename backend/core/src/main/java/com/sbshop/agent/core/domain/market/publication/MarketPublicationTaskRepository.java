package com.sbshop.agent.core.domain.market.publication;

import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface MarketPublicationTaskRepository extends JpaRepository<MarketPublicationTask, String> {
	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from MarketPublicationTask t where t.id=:id")
	Optional<MarketPublicationTask> lock(@Param("id")
	String id);

	@Query("select t from MarketPublicationTask t where t.market=:market and t.state in ('QUEUED','POST_STARTED','VERIFY','AWAITING_APPROVAL') and t.nextRunAt<=:now order by t.nextRunAt,t.createdAt")
	List<MarketPublicationTask> due(@Param("market")
	String market, @Param("now")
	Instant now, Pageable page);

	List<MarketPublicationTask> findTop100ByOrderByCreatedAtDesc();
}

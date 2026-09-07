package com.sbshop.agent.core.domain.market.inspection;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketInspectionTaskRepository extends JpaRepository<MarketInspectionTask, Long> {
	List<MarketInspectionTask> findByBatchIdOrderById(String batchId);

	@Query("select t from MarketInspectionTask t where t.market = :market and ((t.state in ('QUEUED','RETRY_WAIT') and t.nextRunAt <= :now) or (t.state = 'RUNNING' and t.leaseUntil <= :now)) order by t.nextRunAt, t.id")
	List<MarketInspectionTask> due(@Param("market")
	String market, @Param("now")
	Instant now, Pageable page);

	@Query("select count(t) from MarketInspectionTask t where t.state in ('QUEUED','RUNNING','RETRY_WAIT')")
	long activeCount();

	@Query("select count(t) from MarketInspectionTask t where t.market = :market and t.state in ('QUEUED','RUNNING','RETRY_WAIT')")
	long activeCountForMarket(@Param("market")
	String market);

	@Query("select count(t) from MarketInspectionTask t where t.productId = :product and t.state in ('QUEUED','RUNNING','RETRY_WAIT')")
	long activeForProduct(@Param("product")
	Long productId);

	@Query("select distinct t.productId from MarketInspectionTask t where t.market = :market and t.productId in :ids and t.state in ('QUEUED','RUNNING','RETRY_WAIT')")
	List<Long> activeProducts(@Param("market")
	String market, @Param("ids")
	List<Long> ids);

	interface StateCount {
		String getState();

		long getCount();
	}

	@Query("select t.state as state, count(t) as count from MarketInspectionTask t where t.batchId = :id group by t.state")
	List<StateCount> counts(@Param("id")
	String batchId);

	interface BatchStateCount extends StateCount {
		String getBatchId();
	}

	@Query("select t.batchId as batchId, t.state as state, count(t) as count from MarketInspectionTask t join MarketInspectionBatch b on b.id = t.batchId where b.sweepId = :id group by t.batchId, t.state")
	List<BatchStateCount> batchCountsForSweep(@Param("id")
	String sweepId);

	@Query("select t.state as state, count(t) as count from MarketInspectionTask t join MarketInspectionBatch b on b.id = t.batchId where b.sweepId = :id group by t.state")
	List<StateCount> sweepCounts(@Param("id")
	String sweepId);
}

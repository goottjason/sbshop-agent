package com.sbshop.agent.core.domain.market.sync;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketFieldTaskRepository extends JpaRepository<MarketFieldTask, Long> {
	List<MarketFieldTask> findByReviewIdOrderById(String reviewId);

	@Query("select t from MarketFieldTask t where t.market=:market and t.state in ('PREPARE','DRAFT','CHECK','VERIFY','AWAITING_APPROVAL') and t.nextRunAt<=:now order by t.nextRunAt,t.id")
	List<MarketFieldTask> due(@Param("market")
	String market, @Param("now")
	Instant now, Pageable page);

	@Query("select count(t) from MarketFieldTask t where t.productId=:product and t.market=:market and t.state in ('PREPARE','DRAFT','CHECK','VERIFY','AWAITING_APPROVAL')")
	long active(@Param("product")
	Long product, @Param("market")
	String market);
}

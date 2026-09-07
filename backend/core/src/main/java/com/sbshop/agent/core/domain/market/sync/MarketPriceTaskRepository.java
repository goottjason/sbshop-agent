package com.sbshop.agent.core.domain.market.sync;

import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface MarketPriceTaskRepository extends JpaRepository<MarketPriceTask, Long> {
	List<MarketPriceTask> findByReviewIdOrderById(String reviewId);

	long countByReviewId(String reviewId);

	@Query("select t from MarketPriceTask t where t.market=:market and t.state in ('CHECK','VERIFY') and t.nextRunAt<=:now order by t.nextRunAt,t.id")
	List<MarketPriceTask> due(@Param("market")
	String market, @Param("now")
	Instant now, Pageable page);

	@Query("select count(t) from MarketPriceTask t where t.productId=:product and t.market=:market and t.state in ('CHECK','VERIFY')")
	long active(@Param("product")
	Long product, @Param("market")
	String market);
}

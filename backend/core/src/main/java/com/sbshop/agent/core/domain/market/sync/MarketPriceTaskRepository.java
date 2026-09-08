package com.sbshop.agent.core.domain.market.sync;

import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface MarketPriceTaskRepository extends JpaRepository<MarketPriceTask, Long> {
	List<MarketPriceTask> findByReviewIdOrderById(String reviewId);

	long countByReviewId(String reviewId);

	@Query(value = """
		select t.* from sb_market_price_task t
		where t.market=:market and t.state in ('CHECK','VERIFY') and t.next_run_at<=:now
		and not exists (select 1 from sb_supplier_batch_stage s join sb_supplier_batch_run r on r.id=s.batch_id
		    where s.stage='MARKET' and s.reference_id=t.review_id and r.state<>'RUNNING')
		order by t.next_run_at,t.id
		""", nativeQuery = true)
	List<MarketPriceTask> due(@Param("market")
	String market, @Param("now")
	Instant now, Pageable page);

	@Query("select count(t) from MarketPriceTask t where t.productId=:product and t.market=:market and t.state in ('CHECK','VERIFY')")
	long active(@Param("product")
	Long product, @Param("market")
	String market);
}

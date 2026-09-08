package com.sbshop.agent.core.domain.product.batch;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface ProductSupplierBatchStageRepository extends JpaRepository<ProductSupplierBatchStage, Long> {
	List<ProductSupplierBatchStage> findByItemIdOrderById(Long itemId);

	List<ProductSupplierBatchStage> findByItemIdInOrderById(Collection<Long> itemIds);

	// Completed network work must reach the UI before an hours-old submission backlog is drained.
	@Query("select s from ProductSupplierBatchStage s where s.batchId=:batchId and s.stage='MARKET' and s.state='RUNNING' and s.nextRunAt<=:now and ((s.field='PRICE' and exists(select p.id from MarketPriceTask p where p.id=s.taskId and p.reviewId=s.referenceId and p.state not in ('CHECK','VERIFY'))) or (s.field='STOCK' and exists(select t.id from MarketStockTask t where t.id=s.taskId and t.reviewId=s.referenceId and t.state not in ('CHECK','VERIFY')))) order by s.nextRunAt,s.id")
	List<ProductSupplierBatchStage> completedMarkets(String batchId, Instant now, Pageable page);

	@Query("select s from ProductSupplierBatchStage s where s.batchId=:batchId and s.stage='MARKET' and s.state in ('WAITING','RUNNING') and s.nextRunAt<=:now and exists(select d.id from ProductSupplierBatchStage d where d.itemId=s.itemId and d.stage='DB' and d.state in ('SUCCEEDED','UNCHANGED')) order by s.nextRunAt,s.id")
	List<ProductSupplierBatchStage> dueMarkets(String batchId, Instant now, Pageable page);

	@Query("select s from ProductSupplierBatchStage s where s.batchId=:batchId and s.retryable=true and s.state in ('FAILED','BLOCKED') and (:itemId is null or s.itemId=:itemId) and (:stage='' or s.stage=:stage) and (:market='' or s.market=:market) and (:field='' or s.field=:field) order by s.id")
	List<ProductSupplierBatchStage> retryable(String batchId, Long itemId, String stage, String market, String field);

	@Query("select min(s.nextRunAt) from ProductSupplierBatchStage s where s.batchId=:batchId and s.state='RUNNING'")
	Instant nextRunAt(String batchId);
}

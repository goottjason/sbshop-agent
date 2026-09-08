package com.sbshop.agent.core.domain.product.batch;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface ProductSupplierBatchStageRepository extends JpaRepository<ProductSupplierBatchStage, Long> {
	List<ProductSupplierBatchStage> findByItemIdOrderById(Long itemId);

	List<ProductSupplierBatchStage> findByItemIdInOrderById(Collection<Long> itemIds);

	@Query("select s from ProductSupplierBatchStage s where s.batchId=:batchId and s.stage='MARKET' and s.state in ('WAITING','RUNNING') and s.nextRunAt<=:now and exists(select d.id from ProductSupplierBatchStage d where d.itemId=s.itemId and d.stage='DB' and d.state in ('SUCCEEDED','UNCHANGED')) order by s.nextRunAt,s.id")
	List<ProductSupplierBatchStage> dueMarkets(String batchId, Instant now, Pageable page);

	@Query("select s from ProductSupplierBatchStage s where s.batchId=:batchId and s.retryable=true and s.state in ('FAILED','BLOCKED') and (:itemId is null or s.itemId=:itemId) and (:stage='' or s.stage=:stage) and (:market='' or s.market=:market) and (:field='' or s.field=:field) order by s.id")
	List<ProductSupplierBatchStage> retryable(String batchId, Long itemId, String stage, String market, String field);

	@Query("select min(s.nextRunAt) from ProductSupplierBatchStage s where s.batchId=:batchId and s.state='RUNNING'")
	Instant nextRunAt(String batchId);
}

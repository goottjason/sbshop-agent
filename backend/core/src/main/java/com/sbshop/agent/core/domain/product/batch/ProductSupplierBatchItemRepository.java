package com.sbshop.agent.core.domain.product.batch;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ProductSupplierBatchItemRepository extends JpaRepository<ProductSupplierBatchItem, Long> {
	long countByBatchIdAndState(String batchId, String state);

	Optional<ProductSupplierBatchItem> findFirstByBatchIdAndStateOrderByIdAsc(String batchId, String state);

	@Query("select i from ProductSupplierBatchItem i where i.batchId=:batchId and i.state='RUNNING' and exists(select s.id from ProductSupplierBatchStage s where s.itemId=i.id and s.stage in ('CRAWL','DB') and s.state in ('WAITING','RUNNING')) order by i.id")
	List<ProductSupplierBatchItem> findPipeline(String batchId, Pageable page);

	@Query("select i.state,count(i) from ProductSupplierBatchItem i where i.batchId=:batchId group by i.state")
	List<Object[]> counts(String batchId);

	@Query("select i from ProductSupplierBatchItem i where i.batchId=:batchId and (:filter='ALL' or (i.state=:filter and (:filter<>'SUCCEEDED' or exists(select s.id from ProductSupplierBatchStage s where s.itemId=i.id and s.stage='MARKET' and s.state in ('SUCCEEDED','UNCHANGED')))) or (:filter='DB_ONLY' and i.state='SUCCEEDED' and not exists(select s.id from ProductSupplierBatchStage s where s.itemId=i.id and s.stage='MARKET' and s.state in ('SUCCEEDED','UNCHANGED'))) or (:filter='PENDING' and i.state in ('WAITING','RUNNING'))) and (:keyword='' or locate(:keyword,lower(i.sbCode))>0 or locate(:keyword,lower(i.productName))>0) order by i.id")
	Page<ProductSupplierBatchItem> page(String batchId, String filter, String keyword, Pageable pageable);
}

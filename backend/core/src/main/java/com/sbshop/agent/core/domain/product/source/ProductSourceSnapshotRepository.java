package com.sbshop.agent.core.domain.product.source;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductSourceSnapshotRepository extends JpaRepository<ProductSourceSnapshot, String> {
	Optional<ProductSourceSnapshot> findFirstByProductIdOrderByRequestedAtDescIdDesc(Long productId);

	List<ProductSourceSnapshot> findByCollectionIdOrderByRequestedAtAscIdAsc(String collectionId);

	Optional<ProductSourceSnapshot> findFirstByStateOrderByRequestedAtAscIdAsc(ProductSourceSnapshot.State state);

	Optional<ProductSourceSnapshot> findFirstByStateAndVendorOrderByRequestedAtAscIdAsc(
		ProductSourceSnapshot.State state, String vendor);

	@Query("select s from ProductSourceSnapshot s where s.state=:state and s.vendor=:vendor and not exists("
		+ "select b.id from ProductSupplierBatchStage b,ProductSourceCollection c,ProductSupplierBatchRun r "
		+ "where c.id=s.collectionId and b.operationId=c.requestId and b.stage='CRAWL' and r.id=b.batchId and r.state<>'RUNNING') "
		+ "order by s.requestedAt,s.id")
	List<ProductSourceSnapshot> availableForVendor(ProductSourceSnapshot.State state, String vendor,
		org.springframework.data.domain.Pageable page);

	long countByStateIn(List<ProductSourceSnapshot.State> states);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from ProductSourceSnapshot s where s.id=:id")
	Optional<ProductSourceSnapshot> findLocked(@Param("id")
	String id);

	@Query("select s from ProductSourceSnapshot s, ProductSourceCollection c where s.collectionId=c.id "
		+ "and s.productId=:productId and c.actor=:actor order by s.requestedAt desc, s.id desc")
	List<ProductSourceSnapshot> history(@Param("productId")
	Long productId, @Param("actor")
	String actor,
		org.springframework.data.domain.Pageable pageable);
}

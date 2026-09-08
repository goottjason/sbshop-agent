package com.sbshop.agent.core.domain.product.batch;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ProductSupplierBatchRunRepository extends JpaRepository<ProductSupplierBatchRun, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ProductSupplierBatchRun r where r.id=:id")
	Optional<ProductSupplierBatchRun> lock(String id);

	Optional<ProductSupplierBatchRun> findByActorAndRequestId(String actor, String requestId);

	boolean existsByVendorAndStateIn(String vendor, Collection<String> states);

	List<ProductSupplierBatchRun> findByStateInOrderByCreatedAtAsc(Collection<String> states, Pageable page);

	Page<ProductSupplierBatchRun> findAllByOrderByCreatedAtDesc(Pageable page);
}

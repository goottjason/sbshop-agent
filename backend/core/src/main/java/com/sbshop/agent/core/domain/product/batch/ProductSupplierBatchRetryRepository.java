package com.sbshop.agent.core.domain.product.batch;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSupplierBatchRetryRepository extends JpaRepository<ProductSupplierBatchRetry, Long> {
	Optional<ProductSupplierBatchRetry> findByBatchIdAndRequestId(String batchId, String requestId);
}

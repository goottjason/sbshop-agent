package com.sbshop.agent.core.domain.product.batch;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSupplierBatchAttemptRepository extends JpaRepository<ProductSupplierBatchAttempt, Long> {
	List<ProductSupplierBatchAttempt> findByItemIdOrderByIdDesc(Long itemId, Pageable page);
}

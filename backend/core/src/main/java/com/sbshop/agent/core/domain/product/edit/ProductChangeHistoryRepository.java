package com.sbshop.agent.core.domain.product.edit;

import java.util.*;

public interface ProductChangeHistoryRepository
	extends org.springframework.data.jpa.repository.JpaRepository<ProductChangeHistory, Long> {
	Optional<ProductChangeHistory> findByReviewIdAndProductId(String reviewId, Long productId);

	List<ProductChangeHistory> findTop50ByProductIdOrderByIdDesc(Long productId);
}

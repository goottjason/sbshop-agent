package com.sbshop.agent.core.domain.market.marketplus;

import com.sbshop.agent.core.domain.product.edit.ProductChangeHistory;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Bounded history metadata projection omits the historical HTML and full review command columns. */
public interface MarketPlusProgressHistoryRepository extends Repository<ProductChangeHistory, Long> {
	interface Row {
		Long getId();

		Long getProductId();

		long getAfterRevision();

		Instant getCreatedAt();
	}

	@Query("select h.id as id, h.productId as productId, h.afterRevision as afterRevision, h.createdAt as createdAt from ProductChangeHistory h where h.productId=:productId order by h.id desc")
	List<Row> recent(@Param("productId")
	Long productId, Pageable page);
}

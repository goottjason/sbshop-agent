package com.sbshop.agent.core.domain.product.edit;

import java.util.*;

public interface ProductChangeTargetRepository
	extends org.springframework.data.jpa.repository.JpaRepository<ProductChangeTarget, Long> {
	@org.springframework.data.jpa.repository.Query("SELECT t.productId, COUNT(t) FROM ProductChangeTarget t WHERE t.productId IN :ids AND t.state IN ('PENDING_DISPATCH','BATCH_MANAGED','DISPATCHED','ACTION_REQUIRED','AWAITING_REVIEW') GROUP BY t.productId")
	List<Object[]> countPending(@org.springframework.data.repository.query.Param("ids")
	List<Long> ids);

	List<ProductChangeTarget> findByHistoryIdIn(List<Long> historyIds);

	List<ProductChangeTarget> findByRegistrationIdAndMarketAndState(Long registrationId, String market, String state);

	List<ProductChangeTarget> findTop50ByStateOrderById(String state);

	List<ProductChangeTarget> findByPriceTaskId(Long taskId);

	List<ProductChangeTarget> findByStockTaskId(Long taskId);

	List<ProductChangeTarget> findByFieldTaskId(Long taskId);

	List<ProductChangeTarget> findByProductIdAndMarket(Long productId, String market);
}

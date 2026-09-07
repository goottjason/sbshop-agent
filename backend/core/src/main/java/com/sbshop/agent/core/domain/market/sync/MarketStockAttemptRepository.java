package com.sbshop.agent.core.domain.market.sync;

import java.util.*;

public interface MarketStockAttemptRepository
	extends org.springframework.data.jpa.repository.JpaRepository<MarketStockAttempt, Long> {
	List<MarketStockAttempt> findByTaskIdOrderById(Long taskId);
}

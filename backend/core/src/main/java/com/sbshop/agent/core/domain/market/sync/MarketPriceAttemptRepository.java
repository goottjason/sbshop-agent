package com.sbshop.agent.core.domain.market.sync;

import java.util.*;

public interface MarketPriceAttemptRepository
	extends org.springframework.data.jpa.repository.JpaRepository<MarketPriceAttempt, Long> {
	List<MarketPriceAttempt> findByTaskIdOrderById(Long taskId);
}

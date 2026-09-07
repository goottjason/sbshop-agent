package com.sbshop.agent.core.domain.market.sync;

import java.util.*;

public interface MarketFieldAttemptRepository
	extends org.springframework.data.jpa.repository.JpaRepository<MarketFieldAttempt, Long> {
	List<MarketFieldAttempt> findByTaskIdOrderById(Long taskId);
}

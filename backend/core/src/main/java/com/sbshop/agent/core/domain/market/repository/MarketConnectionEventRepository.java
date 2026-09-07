package com.sbshop.agent.core.domain.market.repository;

import com.sbshop.agent.core.domain.market.MarketConnectionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketConnectionEventRepository extends JpaRepository<MarketConnectionEvent, Long> {
	List<MarketConnectionEvent> findTop100ByProductIdOrderByIdDesc(Long productId);
}

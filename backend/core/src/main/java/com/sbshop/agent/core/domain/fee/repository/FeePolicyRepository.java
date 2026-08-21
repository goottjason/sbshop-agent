package com.sbshop.agent.core.domain.fee.repository;

import com.sbshop.agent.core.domain.fee.FeePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePolicyRepository extends JpaRepository<FeePolicy, Long> {

	List<FeePolicy> findByMarketType(MarketType marketType);
}

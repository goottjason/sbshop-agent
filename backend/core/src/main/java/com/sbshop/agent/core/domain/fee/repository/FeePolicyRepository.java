package com.sbshop.agent.core.domain.fee.repository;

import com.sbshop.agent.core.domain.fee.FeePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeePolicyRepository extends JpaRepository<FeePolicy, Long> {

	/** 해당 마켓의 수수료 정책 행. 현재는 마켓 단위 1행을 전제하며, 첫 행의 요율을 사용한다. */
	List<FeePolicy> findByMarketType(MarketType marketType);
}

package com.sbshop.agent.core.domain.market.repository;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketCredentialRepository extends JpaRepository<MarketCredential, Long> {
	Optional<MarketCredential> findByMarketType(MarketType marketType);
}

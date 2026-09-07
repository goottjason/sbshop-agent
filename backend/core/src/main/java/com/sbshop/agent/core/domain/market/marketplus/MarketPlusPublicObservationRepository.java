package com.sbshop.agent.core.domain.market.marketplus;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketPlusPublicObservationRepository extends JpaRepository<MarketPlusPublicObservation, Long> {
	Optional<MarketPlusPublicObservation> findByFingerprint(String fingerprint);

	List<MarketPlusPublicObservation> findTop100ByProductIdOrderByCapturedAtDescIdDesc(Long productId);
}

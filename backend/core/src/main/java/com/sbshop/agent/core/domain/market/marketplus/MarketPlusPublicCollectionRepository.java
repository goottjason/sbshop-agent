package com.sbshop.agent.core.domain.market.marketplus;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketPlusPublicCollectionRepository extends JpaRepository<MarketPlusPublicCollection, String> {
	Optional<MarketPlusPublicCollection> findByActorAndRequestId(String actor, String requestId);

	List<MarketPlusPublicCollection> findTop20ByActorOrderByCreatedAtDesc(String actor);
}

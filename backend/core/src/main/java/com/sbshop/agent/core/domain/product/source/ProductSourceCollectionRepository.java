package com.sbshop.agent.core.domain.product.source;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSourceCollectionRepository extends JpaRepository<ProductSourceCollection, String> {
	Optional<ProductSourceCollection> findByActorAndRequestId(String actor, String requestId);
}

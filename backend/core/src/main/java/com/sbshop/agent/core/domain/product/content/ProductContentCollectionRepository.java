package com.sbshop.agent.core.domain.product.content;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductContentCollectionRepository extends JpaRepository<ProductContentCollection, String> {
	Optional<ProductContentCollection> findByActorAndRequestId(String actor, String requestId);
}

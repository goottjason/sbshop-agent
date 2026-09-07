package com.sbshop.agent.core.domain.product.content;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductContentLaneRepository extends JpaRepository<ProductContentLane, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from ProductContentLane l where l.id=:id")
	Optional<ProductContentLane> findLocked(@Param("id")
	String id);
}

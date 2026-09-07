package com.sbshop.agent.core.domain.market.inspection;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MarketInspectionGateRepository extends JpaRepository<MarketInspectionGate, String> {
	@Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from MarketInspectionGate g where g.id = :id")
	Optional<MarketInspectionGate> lock(@Param("id")
	String id);
}

package com.sbshop.agent.core.domain.sourcing.repository;

import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourcingConfigRepository extends JpaRepository<SourcingConfig, Long> {

	Optional<SourcingConfig> findFirstByOrderByIdAsc();
}

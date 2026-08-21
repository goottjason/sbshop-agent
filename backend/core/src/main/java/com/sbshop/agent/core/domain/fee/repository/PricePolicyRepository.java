package com.sbshop.agent.core.domain.fee.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricePolicyRepository extends JpaRepository<PricePolicy, Long> {

	Optional<PricePolicy> findFirstByStatusOrderByIdAsc(RecordStatus status);
}

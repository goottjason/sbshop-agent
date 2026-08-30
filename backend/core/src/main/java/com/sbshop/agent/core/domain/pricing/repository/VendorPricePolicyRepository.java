package com.sbshop.agent.core.domain.pricing.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorPricePolicyRepository extends JpaRepository<VendorPricePolicy, Long> {

	Optional<VendorPricePolicy> findByVendorAndStatus(VendorType vendor, RecordStatus status);

	List<VendorPricePolicy> findAllByStatusOrderByVendorAsc(RecordStatus status);
}

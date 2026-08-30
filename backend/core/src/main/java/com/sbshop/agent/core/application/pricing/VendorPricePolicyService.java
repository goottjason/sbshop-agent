package com.sbshop.agent.core.application.pricing;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.pricing.repository.VendorPricePolicyRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VendorPricePolicyService {

	private final VendorPricePolicyRepository repository;

	@Transactional(readOnly = true)
	public Optional<VendorPricePolicy> find(VendorType vendor) {
		if (vendor == null) {
			return Optional.empty();
		}
		return repository.findByVendorAndStatus(vendor, RecordStatus.ACTIVE);
	}

	@Transactional(readOnly = true)
	public List<VendorPricePolicy> findAll() {
		return repository.findAllByStatusOrderByVendorAsc(RecordStatus.ACTIVE);
	}
}

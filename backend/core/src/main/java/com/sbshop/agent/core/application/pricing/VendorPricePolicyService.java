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

	@Transactional
	public VendorPricePolicy upsert(VendorType vendor, java.math.BigDecimal marginRate,
		java.math.BigDecimal couponRate, java.math.BigDecimal minMarginPrice, String shipCurrency,
		java.math.BigDecimal shipBaseAmount, Integer shipBaseWeightG,
		java.math.BigDecimal shipStepAmount, Integer shipStepWeightG,
		java.math.BigDecimal domesticFee, java.math.BigDecimal domesticFreeOver) {
		VendorPricePolicy existing = repository.findByVendorAndStatus(vendor, RecordStatus.ACTIVE)
			.orElse(null);
		if (existing == null) {
			return repository.save(VendorPricePolicy.builder()
				.vendor(vendor).marginRate(marginRate).couponRate(couponRate)
				.minMarginPrice(minMarginPrice).shipCurrency(shipCurrency)
				.shipBaseAmount(shipBaseAmount).shipBaseWeightG(shipBaseWeightG)
				.shipStepAmount(shipStepAmount).shipStepWeightG(shipStepWeightG)
				.domesticFee(domesticFee).domesticFreeOver(domesticFreeOver)
				.build());
		}
		existing.update(marginRate, couponRate, minMarginPrice, shipCurrency, shipBaseAmount,
			shipBaseWeightG, shipStepAmount, shipStepWeightG, domesticFee, domesticFreeOver);
		return repository.save(existing);
	}
}

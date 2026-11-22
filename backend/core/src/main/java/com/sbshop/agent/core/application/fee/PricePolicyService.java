package com.sbshop.agent.core.application.fee;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.fee.repository.PricePolicyRepository;
import com.sbshop.agent.core.domain.pricing.PricePolicyValidator;
import com.sbshop.agent.core.domain.product.service.MarginCalculator.MarginFloor;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PricePolicyService {

	private final PricePolicyRepository pricePolicyRepository;

	@Transactional(readOnly = true)
	public PricePolicy get() {
		return pricePolicyRepository.findFirstByStatusOrderByIdAsc(RecordStatus.ACTIVE).orElse(null);
	}

	@Transactional
	public PricePolicy update(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice,
		MarginFloor floor) {
		PricePolicyValidator.validate(marginRate, couponRate, minMarginPrice);
		MarginFloor f = floor == null ? MarginFloor.NONE : floor;
		PricePolicyValidator.validateMarginFloor(f.ratePercent(), f.amount());
		PricePolicy policy = get();
		if (policy == null) {
			return pricePolicyRepository.save(PricePolicy.builder()
				.marginRate(marginRate)
				.couponRate(couponRate)
				.minMarginPrice(minMarginPrice)
				.marginFloorRate(f.ratePercent())
				.marginFloorAmount(f.amount())
				.build());
		}
		policy.update(marginRate, couponRate, minMarginPrice, f);
		return pricePolicyRepository.save(policy);
	}
}

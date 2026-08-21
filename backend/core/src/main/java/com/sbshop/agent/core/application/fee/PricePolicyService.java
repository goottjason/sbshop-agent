package com.sbshop.agent.core.application.fee;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.fee.repository.PricePolicyRepository;
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
	public PricePolicy update(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		PricePolicy policy = get();
		if (policy == null) {
			return pricePolicyRepository.save(PricePolicy.builder()
				.marginRate(marginRate)
				.couponRate(couponRate)
				.minMarginPrice(minMarginPrice)
				.build());
		}
		policy.update(marginRate, couponRate, minMarginPrice);
		return pricePolicyRepository.save(policy);
	}
}

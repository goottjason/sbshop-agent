package com.sbshop.agent.core.application.fee;

import com.sbshop.agent.core.domain.fee.FeePolicy;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.order.SettlementPolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketFeeService {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final FeePolicyRepository feePolicyRepository;

	@Transactional(readOnly = true)
	public BigDecimal feeRate(MarketType marketType) {
		return feePolicyRepository.findByMarketType(marketType).stream()
			.map(FeePolicy::getFeeRate)
			.findFirst()
			.orElseGet(() -> SettlementPolicy.defaultFeeRate(marketType));
	}

	public BigDecimal settlementMultiplier(MarketType marketType) {
		return BigDecimal.ONE.subtract(feeRate(marketType).divide(HUNDRED, 4, RoundingMode.HALF_UP));
	}

	public BigDecimal settlementAmount(BigDecimal grossAmount, MarketType marketType) {
		if (grossAmount == null) {
			return null;
		}
		return grossAmount.multiply(settlementMultiplier(marketType));
	}
}

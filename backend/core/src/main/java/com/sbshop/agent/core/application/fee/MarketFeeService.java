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

/**
 * 마켓별 정산 수수료 계산.
 *
 * <p>정산액은 (금액 × (1 - 수수료율/100))로 <b>한 번만</b> 계산한다. 수수료율은 {@code sb_fee_policy}
 * (FeePolicy)에서 마켓별로 조회하고, 행이 없으면 {@link SettlementPolicy#defaultFeeRate}로 폴백한다.
 * (종전엔 flat 0.89를 sync·ship 두 곳에서 곱해 이중 차감되던 것을 sync 1회 적용으로 바로잡았다.)
 */
@Service
@RequiredArgsConstructor
public class MarketFeeService {

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final FeePolicyRepository feePolicyRepository;

	/** 마켓 수수료율(%). FeePolicy(DB) 우선, 없으면 코드 기본값. */
	@Transactional(readOnly = true)
	public BigDecimal feeRate(MarketType marketType) {
		return feePolicyRepository.findByMarketType(marketType).stream()
			.map(FeePolicy::getFeeRate)
			.findFirst()
			.orElseGet(() -> SettlementPolicy.defaultFeeRate(marketType));
	}

	/** 정산 계수 = 1 - 수수료율/100. (예: 수수료 11% → 0.89) */
	public BigDecimal settlementMultiplier(MarketType marketType) {
		return BigDecimal.ONE.subtract(feeRate(marketType).divide(HUNDRED, 4, RoundingMode.HALF_UP));
	}

	/** 정산액 = 금액 × 계수. 금액이 null이면 null. */
	public BigDecimal settlementAmount(BigDecimal grossAmount, MarketType marketType) {
		if (grossAmount == null) {
			return null;
		}
		return grossAmount.multiply(settlementMultiplier(marketType));
	}
}

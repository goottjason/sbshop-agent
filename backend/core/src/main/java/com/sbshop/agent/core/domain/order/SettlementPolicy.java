package com.sbshop.agent.core.domain.order;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.Map;

public final class SettlementPolicy {
	private static final BigDecimal FALLBACK_FEE_RATE = new BigDecimal("18");

	private static final Map<MarketType, BigDecimal> DEFAULT_FEE_RATES = Map.of(
		MarketType.COUPANG, new BigDecimal("11"),
		MarketType.SMART_STORE, new BigDecimal("8"),
		MarketType.ELEVEN_STREET, new BigDecimal("18"),
		MarketType.GMARKET, new BigDecimal("18"),
		MarketType.AUCTION, new BigDecimal("18"),
		MarketType.CAFE24, new BigDecimal("18"));

	private SettlementPolicy() {}

	public static BigDecimal defaultFeeRate(MarketType marketType) {
		return DEFAULT_FEE_RATES.getOrDefault(marketType, FALLBACK_FEE_RATE);
	}
}

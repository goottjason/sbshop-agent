package com.sbshop.agent.core.domain.order;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 마켓별 정산 수수료율의 코드 기본값(fallback).
 *
 * <p>정산액은 (주문 총액 × (1 - 수수료율/100))로 한 번만 계산한다. 실제 요율은 {@code sb_fee_policy}
 * 테이블(FeePolicy)에서 조회하며({@code MarketFeeService}), DB에 해당 마켓 행이 없을 때 이 기본값으로
 * 안전하게 폴백한다. 요율 변경은 원칙적으로 DB에서 하고, 이 표는 초기값·안전망이다.
 *
 * <p>요율(2026-07 사용자 확정): 쿠팡 11 · 스마트스토어 8 · 11번가·G마켓·옥션·카페24 18(%).
 * 카페24는 G마켓/옥션 주문의 동기화 매개체라 동일 18%.
 */
public final class SettlementPolicy {

	private static final BigDecimal FALLBACK_FEE_RATE = new BigDecimal("18");

	private static final Map<MarketType, BigDecimal> DEFAULT_FEE_RATES = Map.of(
		MarketType.COUPANG, new BigDecimal("11"),
		MarketType.SMART_STORE, new BigDecimal("8"),
		MarketType.ELEVEN_STREET, new BigDecimal("18"),
		MarketType.GMARKET, new BigDecimal("18"),
		MarketType.AUCTION, new BigDecimal("18"),
		MarketType.CAFE24, new BigDecimal("18"));

	/** 마켓별 기본 수수료율(%). 미등록 마켓은 보수적 폴백(18%). */
	public static BigDecimal defaultFeeRate(MarketType marketType) {
		return DEFAULT_FEE_RATES.getOrDefault(marketType, FALLBACK_FEE_RATE);
	}

	private SettlementPolicy() {}
}

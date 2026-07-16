package com.sbshop.agent.core.domain.order;

import java.math.BigDecimal;

/**
 * 정산 관련 공용 상수.
 *
 * <p>정산액은 (판매/주문 금액 × {@link #SETTLEMENT_FEE_RATE})로 계산한다 — 마켓 수수료 11%를 차감한 값(0.89).
 * 종전에는 OrderShipService·CoupangOrderSyncService·Cafe24OrderSyncService 세 곳에 동일 리터럴이
 * 중복 하드코딩돼 있었다(F-ORD-32·F-SYNC-4). 재무 계수는 단일 출처로 관리해야 드리프트를 막는다.
 *
 * <p>TODO(설정화 백로그): 향후 마켓·카테고리별 실제 요율은 FeePolicy(sb_fee_policy)에서 조회하도록
 * 이관한다. 현재 FeePolicy는 휴면 엔티티이며 flat 0.89와 개념(카테고리별 요율)이 달라 별도 설계·요율
 * 검증이 선행돼야 한다.
 */
public final class SettlementPolicy {

	/** 정산액 계산 시 금액에 곱하는 수수료 차감 계수(마켓 수수료 11% → 0.89). */
	public static final BigDecimal SETTLEMENT_FEE_RATE = new BigDecimal("0.89");

	private SettlementPolicy() {
	}
}

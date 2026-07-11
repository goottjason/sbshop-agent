package com.sbshop.agent.core.application.order.service;

/**
 * 마켓 송장 전송 결과.
 * - sent: 마켓 API 전송이 실제로 성공함(이 경우에만 trackingSentToMarket=true 마킹 허용).
 * - skipped: 전송 대상이 아니라 정상적으로 건너뜀(주문 없음/취소상태/어댑터 미지원 등) — 실패 아님.
 * - failed: 마켓 API 호출이 예외로 실패함(자사 배송정보 저장은 보존, 재시도 가능하도록 미마킹).
 * D-069: 마켓 전송 실패가 배송정보 저장을 롤백하지 않도록 예외 대신 결과로 표면화한다.
 */
public record MarketShippingResult(boolean sent, boolean skipped, String failureReason) {

	public static MarketShippingResult ofSent() {
		return new MarketShippingResult(true, false, null);
	}

	public static MarketShippingResult ofSkipped(String reason) {
		return new MarketShippingResult(false, true, reason);
	}

	public static MarketShippingResult ofFailed(String reason) {
		return new MarketShippingResult(false, false, reason);
	}

	/** 마켓 전송이 실패했는지(전송 대상이었으나 API 예외). */
	public boolean isFailed() {
		return !sent && !skipped;
	}
}

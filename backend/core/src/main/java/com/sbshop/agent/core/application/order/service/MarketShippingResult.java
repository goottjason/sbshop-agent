package com.sbshop.agent.core.application.order.service;

/**
 * 마켓 송장 전송 결과.
 * - sent: 마켓 API 전송이 실제로 성공함(이 경우에만 trackingSentToMarket=true 마킹 허용).
 * - skipped: 전송 대상이 아니라 정상적으로 건너뜀(주문 없음/취소상태/어댑터 미지원 등) — 실패 아님.
 * - failed(재시도 가능): 마켓 API 호출이 일시 예외로 실패함(자사 배송정보 저장은 보존, 재시도 위해 미마킹).
 * - terminal(재시도 불가): 마켓이 배송상태 잠금 등으로 영구 거부(예: 쿠팡 배송중/배송완료 → "배송진행상태가
 *   유효하지 않습니다"). 재시도해도 성공 불가하므로 호출자는 재시도 루프를 종결해야 한다(D-E6).
 * D-069: 마켓 전송 실패가 배송정보 저장을 롤백하지 않도록 예외 대신 결과로 표면화한다.
 */
public record MarketShippingResult(boolean sent, boolean skipped, boolean terminal, String failureReason) {

	public static MarketShippingResult ofSent() {
		return new MarketShippingResult(true, false, false, null);
	}

	public static MarketShippingResult ofSkipped(String reason) {
		return new MarketShippingResult(false, true, false, reason);
	}

	/** 일시 실패 — 다음 사이클 재시도 대상. */
	public static MarketShippingResult ofFailed(String reason) {
		return new MarketShippingResult(false, false, false, reason);
	}

	/** 영구 실패(마켓 상태 잠금 등) — 재시도해도 성공 불가하므로 종결 처리 대상. */
	public static MarketShippingResult ofTerminal(String reason) {
		return new MarketShippingResult(false, false, true, reason);
	}

	/** 마켓 전송이 실패했는지(전송 대상이었으나 API 예외 — 일시/영구 모두 포함). */
	public boolean isFailed() {
		return !sent && !skipped;
	}

	/** 재시도해도 성공할 수 없는 영구 실패인지. */
	public boolean isTerminal() {
		return terminal;
	}
}

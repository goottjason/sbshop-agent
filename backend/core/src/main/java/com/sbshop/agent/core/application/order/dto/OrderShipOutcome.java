package com.sbshop.agent.core.application.order.dto;

import lombok.Getter;

/**
 * 주문 1건 발송 처리 결과(F-ORD-31).
 *
 * <p>{@code OrderShipProcessor.shipSingleOrder(orderId)}가 주문 단위 독립 트랜잭션에서 발송을 수행한 뒤
 * 그 결과를 이 값으로 반환하고, 오케스트레이터({@code OrderShipService.bulkShipOrders})가 이를
 * {@link BulkShipResult}로 집계한다. 결과를 값으로 반환함으로써, 실패를 예외로 전파해 오케스트레이션
 * 트랜잭션(없음)이나 다른 주문의 이미 커밋된 발송을 되돌리는 일이 없도록 한다.
 */
@Getter
public class OrderShipOutcome {

	/** 발송 처리 종류. */
	public enum Kind {
		/** 최소 한 라인 이상 실제 발송(외부 전송+저장)됨 — 성공 집계. */
		SHIPPED,
		/** 발송 대상 라인이 하나도 없었음(전부 이미 발송/송장없음) — 정상 스킵 집계. */
		SKIPPED,
		/** 발송 중 실패가 발생함 — 실패 집계(errorMessage 포함). */
		FAILED
	}

	private final Kind kind;
	private final String errorMessage;

	private OrderShipOutcome(Kind kind, String errorMessage) {
		this.kind = kind;
		this.errorMessage = errorMessage;
	}

	public static OrderShipOutcome shipped() {
		return new OrderShipOutcome(Kind.SHIPPED, null);
	}

	public static OrderShipOutcome skipped() {
		return new OrderShipOutcome(Kind.SKIPPED, null);
	}

	public static OrderShipOutcome failed(String errorMessage) {
		return new OrderShipOutcome(Kind.FAILED, errorMessage);
	}

	public boolean isShipped() {
		return kind == Kind.SHIPPED;
	}

	public boolean isSkipped() {
		return kind == Kind.SKIPPED;
	}

	public boolean isFailed() {
		return kind == Kind.FAILED;
	}
}

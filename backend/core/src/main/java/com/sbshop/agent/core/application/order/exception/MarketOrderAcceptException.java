package com.sbshop.agent.core.application.order.exception;

/**
 * 마켓플레이스 주문 접수(발주확인) API 호출이 실패했을 때 던진다(F-ORD-8).
 *
 * <p>종전에는 접수 실패를 밋밋한 {@link RuntimeException}으로 뭉개 실패 유형이 소실됐다. 원인 예외를
 * 그대로 체이닝(cause)해 원 메시지·스택을 보존하고, 접수 실패임을 전용 타입으로 표면화한다.
 *
 * <p>{@link RuntimeException}을 상속하므로 응답 계약은 종전과 동일하다(GlobalExceptionHandler의
 * 일반 핸들러 → HTTP 500). 상태코드 변경이 아니라 로그·메시지 품질 개선이 목적이다.
 */
public class MarketOrderAcceptException extends RuntimeException {

	public MarketOrderAcceptException(String message, Throwable cause) {
		super(message, cause);
	}
}

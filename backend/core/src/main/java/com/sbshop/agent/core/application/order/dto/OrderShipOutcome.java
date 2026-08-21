package com.sbshop.agent.core.application.order.dto;

import lombok.Getter;

@Getter
public class OrderShipOutcome {
	public enum Kind {
		SHIPPED,
		SKIPPED,
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

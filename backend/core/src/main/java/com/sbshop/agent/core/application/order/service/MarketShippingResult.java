package com.sbshop.agent.core.application.order.service;

public record MarketShippingResult(boolean sent, boolean skipped, boolean terminal, String failureReason) {
	public static MarketShippingResult ofSent() {
		return new MarketShippingResult(true, false, false, null);
	}

	public static MarketShippingResult ofSkipped(String reason) {
		return new MarketShippingResult(false, true, false, reason);
	}

	public static MarketShippingResult ofFailed(String reason) {
		return new MarketShippingResult(false, false, false, reason);
	}

	public static MarketShippingResult ofTerminal(String reason) {
		return new MarketShippingResult(false, false, true, reason);
	}

	public boolean isFailed() {
		return !sent && !skipped;
	}

	public boolean isTerminal() {
		return terminal;
	}
}

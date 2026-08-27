package com.sbshop.agent.core.domain.market.client.dto;

public record MarketApprovalResult(
	String marketItemId,
	String priorStatus,
	boolean called,
	MarketApprovalOutcome outcome,
	String responseCode,
	String responseMessage,
	String note) {

	public static MarketApprovalResult requested(String marketItemId, String priorStatus,
		String responseCode, String responseMessage) {
		return new MarketApprovalResult(marketItemId, priorStatus, true, MarketApprovalOutcome.REQUESTED,
			responseCode, responseMessage, null);
	}

	public static MarketApprovalResult skipped(String marketItemId, String priorStatus, String note) {
		return new MarketApprovalResult(marketItemId, priorStatus, false, MarketApprovalOutcome.SKIPPED,
			null, null, note);
	}

	public static MarketApprovalResult retryable(String marketItemId, String priorStatus,
		String responseCode, String responseMessage, String note) {
		return new MarketApprovalResult(marketItemId, priorStatus, true, MarketApprovalOutcome.RETRYABLE,
			responseCode, responseMessage, note);
	}

	public static MarketApprovalResult failed(String marketItemId, String priorStatus, boolean called,
		String responseCode, String responseMessage, String note) {
		return new MarketApprovalResult(marketItemId, priorStatus, called, MarketApprovalOutcome.FAILED,
			responseCode, responseMessage, note);
	}
}

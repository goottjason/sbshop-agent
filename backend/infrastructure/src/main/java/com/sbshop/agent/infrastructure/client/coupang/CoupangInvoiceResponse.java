package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.Collectors;

public record CoupangInvoiceResponse(
	@JsonProperty("code")
	String code,
	@JsonProperty("message")
	String message,
	@JsonProperty("data")
	ResponseData data) {
	public record ResponseData(
		@JsonProperty("responseCode")
		int responseCode,
		@JsonProperty("responseMessage")
		String responseMessage,
		@JsonProperty("responseList")
		List<InvoiceResult> responseList) {
	}

	public record InvoiceResult(
		@JsonProperty("shipmentBoxId")
		long shipmentBoxId,
		@JsonProperty("succeed")
		boolean succeed,
		@JsonProperty("resultCode")
		String resultCode,
		@JsonProperty("resultMessage")
		String resultMessage,
		@JsonProperty("retryRequired")
		boolean retryRequired) {
	}

	public boolean isSuccessful() {
		boolean envelopeOk = "200".equals(code) || "SUCCESS".equals(message);
		if (!envelopeOk) {
			return false;
		}
		if (data != null && data.responseList() != null && !data.responseList().isEmpty()) {
			return data.responseList().stream().allMatch(InvoiceResult::succeed);
		}
		return true;
	}

	public String failureReason() {
		if (data != null && data.responseList() != null) {
			String itemMsgs = data.responseList().stream()
				.filter(r -> !r.succeed())
				.map(InvoiceResult::resultMessage)
				.filter(m -> m != null && !m.isBlank())
				.collect(Collectors.joining("; "));
			if (!itemMsgs.isBlank()) {
				return itemMsgs;
			}
		}
		return (message != null && !message.isBlank()) ? message : "쿠팡 송장 반영 거부(사유 미상)";
	}
}

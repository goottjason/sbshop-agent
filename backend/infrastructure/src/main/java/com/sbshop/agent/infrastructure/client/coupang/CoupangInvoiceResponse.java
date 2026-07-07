package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

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
		return "200".equals(code) || "SUCCESS".equals(message);
	}
}

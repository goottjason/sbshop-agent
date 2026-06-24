package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record CoupangCancelOrderResponse(
	@JsonProperty("code") String code,
	@JsonProperty("message") String message,
	@JsonProperty("data") CancelData data
) {
	public record CancelData(
		@JsonProperty("receiptMap") Map<String, ReceiptInfo> receiptMap,
		@JsonProperty("orderId") long orderId,
		@JsonProperty("failedVendorItemIds") List<Long> failedVendorItemIds
	) {
	}

	public record ReceiptInfo(
		@JsonProperty("receiptId") long receiptId,
		@JsonProperty("receiptType") String receiptType,
		@JsonProperty("vendorItemIds") List<Long> vendorItemIds,
		@JsonProperty("totalCount") int totalCount
	) {
	}

	public boolean isSuccessful() {
		return "200".equals(code) || "SUCCESS".equals(code);
	}
}

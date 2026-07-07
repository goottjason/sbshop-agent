package com.sbshop.agent.core.application.order.port;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CoupangCancelOrderRequest(
	@JsonProperty("orderId")
	long orderId,
	@JsonProperty("vendorItemIds")
	List<Long> vendorItemIds,
	@JsonProperty("receiptCounts")
	List<Integer> receiptCounts,
	@JsonProperty("bigCancelCode")
	String bigCancelCode,
	@JsonProperty("middleCancelCode")
	String middleCancelCode,
	@JsonProperty("userId")
	String userId,
	@JsonProperty("vendorId")
	String vendorId) {
}

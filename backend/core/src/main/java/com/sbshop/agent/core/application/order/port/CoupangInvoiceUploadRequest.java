package com.sbshop.agent.core.application.order.port;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CoupangInvoiceUploadRequest(
	@JsonProperty("vendorId") String vendorId,
	@JsonProperty("orderSheetInvoiceApplyDtos") List<InvoiceApply> orderSheetInvoiceApplyDtos
) {
	public record InvoiceApply(
		@JsonProperty("shipmentBoxId") long shipmentBoxId,
		@JsonProperty("orderId") long orderId,
		@JsonProperty("vendorItemId") long vendorItemId,
		@JsonProperty("deliveryCompanyCode") String deliveryCompanyCode,
		@JsonProperty("invoiceNumber") String invoiceNumber,
		@JsonProperty("splitShipping") boolean splitShipping,
		@JsonProperty("preSplitShipped") boolean preSplitShipped,
		@JsonProperty("estimatedShippingDate") String estimatedShippingDate
	) {
	}
}

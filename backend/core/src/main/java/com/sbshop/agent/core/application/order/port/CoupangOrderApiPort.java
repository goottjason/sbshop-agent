package com.sbshop.agent.core.application.order.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.domain.market.MarketCredential;

import java.util.List;

public interface CoupangOrderApiPort {
	JsonNode fetchOrders(MarketCredential credential, String fromDate, String toDate, String status);

	void shipOrder(MarketCredential credential, CoupangInvoiceUploadRequest request);

	void updateTracking(MarketCredential credential, CoupangUpdateInvoiceRequest request);

	void acceptOrders(MarketCredential credential, List<String> shipmentBoxIds);

	JsonNode querySalesDetails(MarketCredential credential,
		String recognitionDateFrom, String recognitionDateTo);

	JsonNode queryReturns(MarketCredential credential, String fromDate, String toDate);

	JsonNode queryProduct(MarketCredential credential, long sellerProductId);

	void cancelOrder(MarketCredential credential, CoupangCancelOrderRequest request);
}

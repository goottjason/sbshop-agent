package com.sbshop.agent.core.application.order.port;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public interface SmartStoreOrderApiPort {
	JsonNode fetchOrders(String clientId, String secretKey, String fromDate, String toDate);

	void shipOrder(String clientId, String secretKey, String productOrderId, String trackingNo,
		String deliveryCompanyCode);

	void confirmOrders(String clientId, String secretKey, List<String> productOrderIds);

	void cancelOrders(String clientId, String secretKey, List<String> productOrderIds);
}

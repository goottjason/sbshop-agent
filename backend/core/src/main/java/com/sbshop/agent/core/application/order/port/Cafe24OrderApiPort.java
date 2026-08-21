package com.sbshop.agent.core.application.order.port;

import com.fasterxml.jackson.databind.JsonNode;

public interface Cafe24OrderApiPort {
	JsonNode fetchOrders(String startDate, String endDate, int limit, int offset);

	JsonNode fetchOrderDetail(String orderId);

	JsonNode fetchShipments(String orderId);

	JsonNode fetchCarriers();

	String registerShipment(String orderId, Object requestBody);

	void updateShipment(String orderId, String shippingCode, Object requestBody);

	void acceptOrder(String cafe24OrderId);

	void cancelOrder(String cafe24OrderId);
}

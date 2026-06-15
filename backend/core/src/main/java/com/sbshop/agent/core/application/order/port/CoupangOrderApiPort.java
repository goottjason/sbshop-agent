package com.sbshop.agent.core.application.order.port;

import com.fasterxml.jackson.databind.JsonNode;

public interface CoupangOrderApiPort {
	JsonNode fetchOrders(String vendorId, String accessKey, String secretKey, String fromDate, String toDate,
		String status);

	void shipOrder(String vendorId, String accessKey, String secretKey, String marketOrderNo, String vendorItemId,
		String trackingNo, String deliveryCompanyCode);

	void acceptOrders(String vendorId, String accessKey, String secretKey, java.util.List<String> shipmentBoxIds);

	JsonNode querySalesDetails(String vendorId, String accessKey, String secretKey,
		String recognitionDateFrom, String recognitionDateTo);
}

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

	/**
	 * 쿠팡 상품상세조회 API (seller_api)
	 * sellerProductId로 상품 정보를 조회하여 externalVendorSku(판매자상품코드)를 반환
	 */
	JsonNode queryProduct(String vendorId, String accessKey, String secretKey, long sellerProductId);
}

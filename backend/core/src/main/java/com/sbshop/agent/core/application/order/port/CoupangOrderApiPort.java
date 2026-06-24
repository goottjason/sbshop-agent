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

	/**
	 * 쿠팡 상품상세조회 API (seller_api)
	 * sellerProductId로 상품 정보를 조회하여 externalVendorSku(판매자상품코드)를 반환
	 */
	JsonNode queryProduct(MarketCredential credential, long sellerProductId);

	/**
	 * 쿠팡 주문 취소 API (v5)
	 * 결제완료/상품준비중 상태의 주문을 취소합니다.
	 */
	void cancelOrder(MarketCredential credential, CoupangCancelOrderRequest request);
}

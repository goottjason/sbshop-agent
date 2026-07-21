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
	 * 쿠팡 반품요청 목록 조회 API (v4 returnRequests, searchType=timeFrame).
	 * D-097: 배송완료 후 반품을 전방 감지하기 위한 권위 경로. createdAt 기준 [from, to] 구간의
	 * 반품요청을 반환한다(receiptStatus RETURNS_COMPLETED 등 원본 그대로). 어댑터가 필터링한다.
	 */
	JsonNode queryReturns(MarketCredential credential, String fromDate, String toDate);

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

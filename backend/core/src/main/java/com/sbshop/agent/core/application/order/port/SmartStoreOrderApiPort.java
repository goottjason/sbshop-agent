package com.sbshop.agent.core.application.order.port;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public interface SmartStoreOrderApiPort {
	/**
	 * 스마트스토어의 최근 변경된 주문들의 상세 내역을 가져옵니다.
	 *
	 * @param clientId 스마트스토어 클라이언트 ID
	 * @param secretKey 스마트스토어 시크릿 키
	 * @param fromDate 조회 시작일 (ISO 8601 포맷)
	 * @param toDate 조회 종료일 (ISO 8601 포맷)
	 * @return 주문 상세 내역 JSON 노드 배열 (productOrders)
	 */
	JsonNode fetchOrders(String clientId, String secretKey, String fromDate, String toDate);

	/**
	 * 주문 발송 처리 (송장 번호 입력)
	 */
	void shipOrder(String clientId, String secretKey, String productOrderId, String trackingNo,
		String deliveryCompanyCode);

	/**
	 * 발주 확인 처리 (최대 50건)
	 */
	void confirmOrders(String clientId, String secretKey, List<String> productOrderIds);

	/**
	 * 주문 취소 처리
	 */
	void cancelOrders(String clientId, String secretKey, List<String> productOrderIds);
}

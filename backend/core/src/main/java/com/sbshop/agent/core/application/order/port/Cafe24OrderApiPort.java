package com.sbshop.agent.core.application.order.port;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Cafe24 주문 Admin API 포트.
 * Cafe24에는 G마켓/옥션이 오픈마켓 연동돼 있어, Cafe24 주문 API로 그 주문들을 안정적으로 조회한다
 * (기존 ESM+ Selenium 스크래핑 대체). order_place_id로 마켓(gmarket/auction)을 구분한다.
 */
public interface Cafe24OrderApiPort {

	/**
	 * 주문 목록 조회. GET /api/v2/admin/orders (embed=items,receivers,buyer).
	 *
	 * @param startDate "yyyy-MM-dd HH:mm:ss" (1콜 최대 3개월)
	 * @param endDate   "yyyy-MM-dd HH:mm:ss"
	 * @param limit     1~1000
	 * @param offset    0~15000
	 * @return 응답의 "orders" 배열 JsonNode (없으면 빈 배열/미싱 노드)
	 */
	JsonNode fetchOrders(String startDate, String endDate, int limit, int offset);
}

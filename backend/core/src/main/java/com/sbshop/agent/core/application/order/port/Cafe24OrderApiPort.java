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

	/** 주문 상세 조회(embed=items) — 송장 등록에 필요한 order_item_code 획득용. GET /admin/orders/{id}. */
	JsonNode fetchOrderDetail(String orderId);

	/**
	 * 주문의 배송건 목록. GET /admin/orders/{orderId}/shipments.
	 *
	 * D-124: 주문 목록의 items[].tracking_no는 배송건이 여러 개일 때 하나만 비칠 수 있다.
	 * 마켓(G마켓/옥션)에서 송장이 바뀐 경우 실값이 별도 배송건에 있을 가능성이 있어 이쪽도 조회한다.
	 *
	 * @return 응답의 "shipments" 배열 JsonNode (없으면 빈 배열/미싱 노드)
	 */
	JsonNode fetchShipments(String orderId);

	/** 몰에 등록된 택배사 목록. GET /admin/carriers. shipping_company_code 해석용. */
	JsonNode fetchCarriers();

	/** 송장 등록. POST /admin/orders/{orderId}/shipments. requestBody는 {"request":{...}} 형태. */
	String registerShipment(String orderId, Object requestBody);

	/**
	 * 배송건 수정. PUT /admin/orders/{orderId}/shipments/{shippingCode}.
	 *
	 * <p>D-151: 이미 배송중인 주문에는 배송건을 <b>새로 만들 수 없다</b>
	 * ({@code 422 You cannot change to that order state}). 11번가·네이버와 달리 Cafe24는
	 * 수정 경로가 있으므로, 배송건이 있으면 등록이 아니라 이 API로 송장을 고친다.
	 */
	void updateShipment(String orderId, String shippingCode, Object requestBody);

	/** 발주확인 — 주문 상태를 배송준비 상태로 변경. PUT /admin/orders/{id}. */
	void acceptOrder(String cafe24OrderId);

	/** 주문 취소 — 주문 상태를 취소로 변경. PUT /admin/orders/{id}. */
	void cancelOrder(String cafe24OrderId);
}

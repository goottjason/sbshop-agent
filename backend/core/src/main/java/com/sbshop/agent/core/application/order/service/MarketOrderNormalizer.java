package com.sbshop.agent.core.application.order.service;

import java.util.List;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;

/**
 * 평면 {@link MarketOrderDto}를 (주문 / 배송 / 상품주문) 3계층으로 정규화한다.
 *
 * <p>어댑터를 마켓별로 순차 전환하는 동안 두 형태가 공존한다. 소비자가 분기를 갖지 않도록
 * 여기서 흡수한다 — 평면 DTO는 <b>배송 1 : 상품주문 1</b>로 감싸고, 이미 3계층인 DTO는
 * 그대로 통과시킨다.
 *
 * <p><b>배송</b> 식별자를 얻지 못하면 주문번호로 대체한다(설계 §3.3). 배송 계층이 항상 존재해야
 * 상위 로직("이 배송의 상품들")에 null 분기가 생기지 않고, 전환 전 마켓은 주문당 배송 1건이므로
 * {@code (order_id, market_shipment_no)} 유니크와 충돌하지 않는다.
 *
 * <p><b>상품주문</b> 식별자는 반대로 <b>비워 둔다</b>(D-131). 대체값을 넣으면 그 값이
 * {@code uk_line_item_order_market_no}에 영속돼, 주문당 라인아이템이 2건이 되는 순간
 * 동기화가 유니크 위반으로 통째로 실패한다. 모르는 값은 모른다고 두고, 레거시·미전환 행의
 * 매칭은 {@link OrderLineItemMatcher}가 맡는다.
 */
public final class MarketOrderNormalizer {

	private MarketOrderNormalizer() {}

	public static MarketOrderDto normalize(MarketOrderDto dto) {
		if (dto == null) {
			return null;
		}
		if (dto.getShipments() != null) {
			return dto;
		}

		String shipmentNo = resolveShipmentNo(dto);

		MarketLineItemDto lineItem = MarketLineItemDto.builder()
			// D-131: 상품주문 식별자는 비운다. 전환 전 마켓의 평면 DTO는 그 값을 알려주지 않으며,
			// 배송 식별자를 여기 전용하면 uk_line_item_order_market_no와 충돌한다(주문당 2건부터).
			// null은 "아직 모름"이고, 그때의 매칭은 OrderLineItemMatcher가 담당한다.
			.marketProductCode(dto.getMarketProductCode())
			.sellerProductId(dto.getSellerProductId())
			.productName(dto.getProductName())
			.quantity(dto.getQuantity())
			.orderPrice(dto.getOrderPrice())
			.totalAmount(dto.getTotalAmount())
			.status(dto.getStatus())
			.marketSpecificData(copyMarketSpecificData(dto.getMarketSpecificData()))
			.build();

		MarketShipmentDto shipment = MarketShipmentDto.builder()
			.marketShipmentNo(shipmentNo)
			.trackingNo(dto.getTrackingNo())
			.carrier(dto.getCarrier())
			.lineItems(List.of(lineItem))
			.build();

		// 원본을 건드리지 않는다 — 호출자가 평면 필드를 계속 쓰고 있을 수 있다.
		// marketSpecificData도 라인아이템과 대칭으로 방어적 복사한다 — toBuilder()는 얕은
		// 복사라 그대로 두면 이 계층만 원본과 참조를 공유해, 2단계에서 어댑터가 채운
		// dlvNo·ordPrdSeq를 소비자가 변형할 때 원본이 오염될 수 있다.
		return dto.toBuilder()
			.marketSpecificData(copyMarketSpecificData(dto.getMarketSpecificData()))
			.shipments(List.of(shipment))
			.build();
	}

	/**
	 * 배송 식별자를 고른다. 쿠팡은 {@code shipmentBoxId}가 이미 평면 DTO에 있고,
	 * 나머지 마켓은 전환 전이라 주문번호로 대체한다(배송 1 : 상품주문 1).
	 */
	private static String resolveShipmentNo(MarketOrderDto dto) {
		String boxId = dto.getShipmentBoxId();
		if (boxId != null && !boxId.isBlank()) {
			return boxId;
		}
		return dto.getMarketOrderNo();
	}

	/**
	 * marketSpecificData를 방어적으로 복사한다.
	 *
	 * <p>원본 DTO와 정규화된 DTO의 마켓 데이터가 독립적이어야 한다.
	 * 어댑터가 {@code new HashMap<>()}으로 생성하므로 null 값을 포함할 수 있어
	 * {@link java.util.Map#copyOf(Map)}이 아닌 {@code new HashMap<>(Map)}을 사용한다.
	 */
	private static java.util.Map<String, Object> copyMarketSpecificData(
		java.util.Map<String, Object> original) {
		if (original == null) {
			return null;
		}
		return new java.util.HashMap<>(original);
	}
}

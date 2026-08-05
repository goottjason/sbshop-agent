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
 * <p>배송 식별자를 얻지 못하면 주문번호로 대체한다. 배송 계층이 항상 존재해야
 * 상위 로직("이 배송의 상품들")에 null 분기가 생기지 않는다.
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
			.marketLineItemNo(shipmentNo)
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
		return dto.toBuilder()
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

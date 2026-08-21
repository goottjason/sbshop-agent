package com.sbshop.agent.core.application.order.service;

import java.util.HashMap;
import java.util.List;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import java.util.Map;

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

		return dto.toBuilder()
			.marketSpecificData(copyMarketSpecificData(dto.getMarketSpecificData()))
			.shipments(List.of(shipment))
			.build();
	}

	private static String resolveShipmentNo(MarketOrderDto dto) {
		return dto.getMarketOrderNo();
	}

	private static Map<String, Object> copyMarketSpecificData(
		Map<String, Object> original) {
		if (original == null) {
			return null;
		}
		return new HashMap<>(original);
	}
}

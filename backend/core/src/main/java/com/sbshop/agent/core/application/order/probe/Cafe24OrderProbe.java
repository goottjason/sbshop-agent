package com.sbshop.agent.core.application.order.probe;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24LineItemMapper;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class Cafe24OrderProbe implements MarketOrderProbe {
	private final Cafe24OrderApiPort cafe24OrderApiPort;

	@Override
	public List<MarketType> marketTypes() {
		return List.of(MarketType.GMARKET, MarketType.AUCTION);
	}

	@Override
	public OrderProbeResult probe(Order order) {
		String cafe24OrderId = order.getCafe24OrderId();
		if (cafe24OrderId == null || cafe24OrderId.isBlank()) {
			return OrderProbeResult.unknown("카페24 주문 아이디 없음");
		}
		JsonNode detail;
		try {
			detail = cafe24OrderApiPort.fetchOrderDetail(cafe24OrderId);
		} catch (Exception e) {
			return OrderProbeResult.unknown(String.valueOf(e.getMessage()));
		}
		if (detail == null || detail.isMissingNode() || detail.isNull()
			|| detail.path("order_id").asText("").isBlank()) {
			return OrderProbeResult.notFound("order 없음");
		}
		ShippingStatus itemTerminal = null;
		ShippingStatus itemStage = null;
		for (JsonNode item : detail.path("items")) {
			ShippingStatus mappedItem = Cafe24LineItemMapper.mapStatus(item.path("order_status").asText(""));
			if (isTerminated(mappedItem)) {
				itemTerminal = mappedItem;
				break;
			}
			if (mappedItem != ShippingStatus.UNKNOWN && itemStage == null) {
				itemStage = mappedItem;
			}
		}
		if (itemTerminal != null) {
			return OrderProbeResult.terminated(itemTerminal, "order_status");
		}
		if ("T".equalsIgnoreCase(detail.path("canceled").asText(""))) {
			boolean returned = !detail.path("return_confirmed_date").asText("").isBlank();
			return OrderProbeResult.terminated(
				returned ? ShippingStatus.RETURNED : ShippingStatus.CANCELED,
				detail.path("canceled").asText(""));
		}
		if (itemStage != null) {
			return OrderProbeResult.found(itemStage);
		}
		ShippingStatus mapped = mapShippingStatus(detail.path("shipping_status").asText(""));
		if (mapped == null) {
			return OrderProbeResult.unknown("알 수 없는 shipping_status: "
				+ detail.path("shipping_status").asText(""));
		}
		return OrderProbeResult.found(mapped);
	}

	private boolean isTerminated(ShippingStatus status) {
		return status == ShippingStatus.CANCELED
			|| status == ShippingStatus.RETURNED
			|| status == ShippingStatus.EXCHANGED;
	}

	private ShippingStatus mapShippingStatus(String code) {
		return switch (code.toUpperCase()) {
			case "F" -> ShippingStatus.PREPARING;
			case "M" -> ShippingStatus.SHIPPED;
			case "T" -> ShippingStatus.DELIVERED;
			default -> null;
		};
	}
}

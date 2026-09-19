package com.sbshop.agent.core.application.order.probe;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24LineItemMapper;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class Cafe24OrderProbe implements MarketOrderProbe {
	private static final Pattern CAFE24_ORDER_ID = Pattern.compile("^\\d{8}-\\d{7}$");

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
		if (!isCafe24OrderId(cafe24OrderId)) {
			return OrderProbeResult.unknown("cafe24 주문번호 형식 아님: " + cafe24OrderId);
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
		ClaimData claim = null;
		ShippingStatus itemStage = null;
		String marketTracking = null;
		for (JsonNode item : detail.path("items")) {
			String code = item.path("order_status").asText("");
			ClaimData itemClaim = Cafe24LineItemMapper.mapClaim(code);
			if (itemClaim.getClaimType().isActive() && claim == null) {
				claim = itemClaim;
			}
			ShippingStatus mappedItem = Cafe24LineItemMapper.mapStatus(code);
			if (mappedItem != ShippingStatus.UNKNOWN && itemStage == null) {
				itemStage = mappedItem;
			}
			String tracking = item.path("tracking_no").asText("");
			if (marketTracking == null && !tracking.isBlank()) {
				marketTracking = tracking;
			}
		}
		if ("T".equalsIgnoreCase(detail.path("canceled").asText(""))) {
			boolean returned = !detail.path("return_confirmed_date").asText("").isBlank();
			return OrderProbeResult.terminated(
				returned ? ShippingStatus.RETURNED : ShippingStatus.CANCELED,
				claim, detail.path("canceled").asText(""));
		}
		ShippingStatus stage = itemStage != null
			? itemStage : mapShippingStatus(detail.path("shipping_status").asText(""));
		if (stage == null) {
			return OrderProbeResult.unknown("알 수 없는 shipping_status: "
				+ detail.path("shipping_status").asText(""));
		}
		return OrderProbeResult.found(stage, claim, marketTracking);
	}

	private static boolean isCafe24OrderId(String value) {
		return CAFE24_ORDER_ID.matcher(value).matches();
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

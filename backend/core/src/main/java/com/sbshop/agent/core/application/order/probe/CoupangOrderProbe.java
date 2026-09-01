package com.sbshop.agent.core.application.order.probe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CoupangOrderProbe implements MarketOrderProbe {
	private static final String TERMINATED_MARKER = "취소 또는 반품";
	private static final String NOT_FOUND_MARKER = "유효하지 않은 주문번호";

	private final CoupangOrderApiPort coupangOrderApiPort;
	private final MarketCredentialRepository credentialRepository;
	private final CoupangStatusMapper statusMapper;

	@Override
	public List<MarketType> marketTypes() {
		return List.of(MarketType.COUPANG);
	}

	@Override
	public OrderProbeResult probe(Order order) {
		String marketOrderNo = order.getMarketOrderNo();
		if (marketOrderNo == null || marketOrderNo.isBlank()) {
			return OrderProbeResult.unknown("주문번호 없음");
		}
		Optional<MarketCredential> credential = credentialRepository.findByMarketType(MarketType.COUPANG);
		if (credential.isEmpty()) {
			return OrderProbeResult.unknown("쿠팡 크레덴셜 없음");
		}
		JsonNode body = coupangOrderApiPort.fetchOrderById(credential.get(), marketOrderNo);
		if (body == null) {
			return OrderProbeResult.unknown("응답 없음");
		}
		JsonNode data = body.path("data");
		JsonNode first = data.isArray() && !data.isEmpty() ? data.get(0) : (data.isObject() ? data : null);
		if (first != null && !first.path("status").asText("").isBlank()) {
			ShippingStatus mapped = statusMapper.mapStatus(Map.of("status", first.path("status").asText()));
			return OrderProbeResult.found(mapped);
		}
		String message = body.path("message").asText("");
		if (message.contains(TERMINATED_MARKER)) {
			return OrderProbeResult.terminated(null, message);
		}
		if (message.contains(NOT_FOUND_MARKER)) {
			return OrderProbeResult.notFound(message);
		}
		return OrderProbeResult.unknown(message);
	}
}

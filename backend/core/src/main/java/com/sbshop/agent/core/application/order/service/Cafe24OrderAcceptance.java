package com.sbshop.agent.core.application.order.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.LongConsumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class Cafe24OrderAcceptance {
	private static final int POLL_ATTEMPTS = 5;
	private static final long POLL_INTERVAL_MS = 1500;
	private static final Set<ShippingStatus> ACCEPTED = EnumSet.of(ShippingStatus.PREPARING,
		ShippingStatus.SHIPPED, ShippingStatus.DELIVERED, ShippingStatus.CONFIRMED);

	private final Cafe24OrderApiPort port;
	private final int pollAttempts;
	private final LongConsumer sleeper;

	@Autowired
	public Cafe24OrderAcceptance(Cafe24OrderApiPort port) {
		this(port, POLL_ATTEMPTS, Cafe24OrderAcceptance::sleep);
	}

	Cafe24OrderAcceptance(Cafe24OrderApiPort port, int pollAttempts, LongConsumer sleeper) {
		this.port = port;
		this.pollAttempts = pollAttempts;
		this.sleeper = sleeper;
	}

	public void accept(String cafe24OrderId) {
		if (isAccepted(cafe24OrderId)) {
			log.info("[Cafe24] 이미 배송준비중이라 발주확인을 다시 보내지 않음: orderId={}", cafe24OrderId);
			return;
		}
		port.acceptOrder(cafe24OrderId);
		for (int attempt = 0; attempt < pollAttempts && !Thread.currentThread().isInterrupted(); attempt++) {
			sleeper.accept(POLL_INTERVAL_MS);
			if (isAccepted(cafe24OrderId)) {
				return;
			}
		}
		throw new IllegalStateException("카페24가 발주확인 요청을 받았지만 아직 배송준비중으로 바뀌지 않았습니다. "
			+ "잠시 뒤 새로고침하세요. 다시 눌러도 중복 처리되지 않습니다.");
	}

	private boolean isAccepted(String cafe24OrderId) {
		try {
			JsonNode items = port.fetchOrderDetail(cafe24OrderId).path("items");
			if (!items.isArray()) {
				return false;
			}
			int judged = 0;
			for (JsonNode item : items) {
				String code = item.path("order_status").asText(null);
				ShippingStatus status = Cafe24LineItemMapper.mapStatus(code);
				if (status == ShippingStatus.UNKNOWN
					&& Cafe24LineItemMapper.mapClaim(code).getClaimType() != ClaimType.NONE) {
					continue;
				}
				if (!ACCEPTED.contains(status)) {
					return false;
				}
				judged++;
			}
			return judged > 0;
		} catch (Exception e) {
			log.warn("[Cafe24] 주문 상태 조회 실패: orderId={}, msg={}", cafe24OrderId, e.getMessage());
			return false;
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

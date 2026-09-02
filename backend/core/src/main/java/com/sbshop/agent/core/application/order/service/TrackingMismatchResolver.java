package com.sbshop.agent.core.application.order.service;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingMismatchResolver {
	private final MarketplaceShippingService marketplaceShippingService;
	private final OrderLineItemRepository orderLineItemRepository;
	private final LineItemShippingWriter shippingWriter;

	public void resolve(MarketType marketType, Shipment shipment) {
		TrackingMismatchPolicy policy = TrackingMismatchPolicy.of(marketType, shipment);
		if (policy == TrackingMismatchPolicy.NONE) {
			return;
		}
		for (OrderLineItem item : orderLineItemRepository.findByShipmentId(shipment.getId())) {
			if (policy == TrackingMismatchPolicy.AUTO_RESEND && resend(item, shipment)) {
				continue;
			}
			shippingWriter.markManualFixRequired(item);
		}
	}

	private boolean resend(OrderLineItem item, Shipment shipment) {
		try {
			MarketShippingResult result = marketplaceShippingService.sendTrackingToMarketplace(item, true);
			if (result != null && result.sent()) {
				log.info("송장 불일치 자동 재전송 성공: shipmentId={}, 마켓={} → 우리={}",
					shipment.getId(), shipment.getMarketTrackingNo(), shipment.getTrackingNo());
				return true;
			}
			log.warn("송장 불일치 자동 재전송 거부: shipmentId={}, 사유={}",
				shipment.getId(), result != null ? result.failureReason() : "응답 없음");
		} catch (Exception e) {
			log.warn("송장 불일치 자동 재전송 실패: shipmentId={} — {}", shipment.getId(), e.getMessage());
		}
		return false;
	}
}

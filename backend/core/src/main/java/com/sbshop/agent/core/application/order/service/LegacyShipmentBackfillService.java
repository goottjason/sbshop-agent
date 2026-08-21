package com.sbshop.agent.core.application.order.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyShipmentBackfillService {
	private final OrderLineItemRepository lineItemRepository;
	private final OrderRepository orderRepository;
	private final ShipmentRepository shipmentRepository;

	@Transactional
	public Map<String, Object> backfill() {
		List<OrderLineItem> unlinked = lineItemRepository.findByShipmentIdIsNull();
		int created = 0;
		int linked = 0;
		int skipped = 0;

		for (OrderLineItem item : unlinked) {
			Order order = orderRepository.findById(item.getOrderId()).orElse(null);
			if (order == null) {
				log.warn("[배송백필] 주문 없는 라인아이템 건너뜀: lineItemId={}, orderId={}",
					item.getId(), item.getOrderId());
				skipped++;
				continue;
			}
			String shipmentNo = resolveShipmentNo(order);
			if (shipmentNo == null || shipmentNo.isBlank()) {
				log.warn("[배송백필] 배송 식별자를 지어낼 수 없어 건너뜀: orderId={}, market={}"
					+ " — 동기화가 배송을 만들 때까지 남긴다", order.getId(), order.getMarketType());
				skipped++;
				continue;
			}

			Shipment shipment = shipmentRepository
				.findByOrderIdAndMarketShipmentNo(order.getId(), shipmentNo)
				.orElse(null);
			if (shipment == null) {
				shipment = Shipment.builder()
					.orderId(order.getId())
					.marketShipmentNo(shipmentNo)
					.build();
				created++;
			}

			ShippingData shipping = item.getShippingData();
			if (shipping != null) {
				shipment.applyTracking(shipping.getTrackingNo(), shipping.getShippingCarrier(),
					shipping.getTrackingSentToMarket());
			}
			shipmentRepository.save(shipment);

			item.assignShipmentId(shipment.getId());
			lineItemRepository.save(item);
			linked++;
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("unlinked", unlinked.size());
		result.put("created", created);
		result.put("linked", linked);
		result.put("skipped", skipped);
		log.info("[배송백필] 완료: 미연결 {}건 → 배송 신규 {}건, 연결 {}건, 건너뜀 {}건",
			unlinked.size(), created, linked, skipped);
		return result;
	}

	private String resolveShipmentNo(Order order) {
		if (order.getMarketType() == MarketType.COUPANG) {
			return null;
		}
		return order.getMarketOrderNo();
	}
}

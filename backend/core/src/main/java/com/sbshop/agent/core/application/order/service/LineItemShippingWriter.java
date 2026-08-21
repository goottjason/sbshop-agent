package com.sbshop.agent.core.application.order.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineItemShippingWriter {
	private final ShipmentRepository shipmentRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	@Transactional
	public void applyShipping(OrderLineItem item, ShippingData data) {
		applyShipping(item, data, null);
	}

	@Transactional
	public void applyShipping(OrderLineItem item, ShippingData data, TrackingSource source) {
		item.applyShippingData(data);
		orderLineItemRepository.save(item);
		writeThrough(item, data.getTrackingNo(), data.getShippingCarrier(),
			data.getTrackingSentToMarket(), source);
	}

	@Transactional(readOnly = true)
	public boolean marketHasTracking(OrderLineItem item, String trackingNo) {
		Shipment shipment = item.getShipmentId() != null
			? shipmentRepository.findById(item.getShipmentId()).orElse(null)
			: null;
		String marketTracking = shipment != null ? shipment.getMarketTrackingNo() : null;
		if (marketTracking == null || marketTracking.isBlank()) {
			ShippingData shipping = item.getShippingData();
			return shipping != null && Boolean.TRUE.equals(shipping.getTrackingSentToMarket());
		}
		return marketTracking.equals(trackingNo);
	}

	@Transactional
	public void promoteTrackingSourceToEmail(OrderLineItem item) {
		Long shipmentId = item.getShipmentId();
		if (shipmentId == null) {
			return;
		}
		shipmentRepository.findById(shipmentId).ifPresent(shipment -> {
			shipment.applyTrackingSource(TrackingSource.EMAIL);
			shipmentRepository.save(shipment);
		});
	}

	@Transactional(readOnly = true)
	public boolean isAwaitingManualFix(OrderLineItem item) {
		if (item.getShipmentId() == null) {
			return false;
		}
		return shipmentRepository.findById(item.getShipmentId())
			.map(Shipment::isManualFixRequired)
			.orElse(false);
	}

	@Transactional
	public void markManualFixRequired(OrderLineItem item) {
		if (item.getShipmentId() == null) {
			return;
		}
		shipmentRepository.findById(item.getShipmentId()).ifPresent(shipment -> {
			shipment.markManualFixRequired();
			shipmentRepository.save(shipment);
		});
	}

	@Transactional
	public void markTrackingAsSent(OrderLineItem item) {
		item.markTrackingAsSent();
		orderLineItemRepository.save(item);
		writeThrough(item, null, null, Boolean.TRUE, null);
	}

	private void writeThrough(OrderLineItem item, String trackingNo,
		ShippingCarrier carrier, Boolean sentToMarket, TrackingSource source) {
		Long shipmentId = item.getShipmentId();
		if (shipmentId == null) {
			return;
		}

		Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
		if (shipment == null) {
			log.warn("라인아이템 {} 의 배송 {} 을 찾을 수 없어 미러를 건너뛴다 — 라인아이템 기록은 유지된다",
				item.getId(), shipmentId);
			return;
		}

		shipment.applyTracking(trackingNo, carrier, sentToMarket);
		shipment.applyTrackingSource(source);
		shipmentRepository.save(shipment);

		mirrorToSiblings(item, shipmentId, shipment);
	}

	private void mirrorToSiblings(OrderLineItem edited, Long shipmentId, Shipment shipment) {
		List<OrderLineItem> siblings = orderLineItemRepository.findByShipmentId(shipmentId);
		for (OrderLineItem sibling : siblings) {
			if (isSameRow(sibling, edited)) {
				continue;
			}
			ShippingData current = sibling.getShippingData() != null
				? sibling.getShippingData()
				: ShippingData.builder().build();
			ShippingData.ShippingDataBuilder mirrored = current.toBuilder();
			if (shipment.getTrackingNo() != null) {
				mirrored.trackingNo(shipment.getTrackingNo());
			}
			if (shipment.getShippingCarrier() != null) {
				mirrored.shippingCarrier(shipment.getShippingCarrier());
			}
			if (shipment.getTrackingSentToMarket() != null) {
				mirrored.trackingSentToMarket(shipment.getTrackingSentToMarket());
			}
			sibling.applyShippingData(mirrored.build());
			orderLineItemRepository.save(sibling);
		}
	}

	private static boolean isSameRow(OrderLineItem a, OrderLineItem b) {
		if (a == b) {
			return true;
		}
		return a.getId() != null && a.getId().equals(b.getId());
	}
}

package com.sbshop.agent.core.application.order.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderShipmentUpsertService {
	private final ShipmentRepository shipmentRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	@Transactional
	public Shipment upsertShipment(Long orderId, MarketShipmentDto dto) {
		String shipmentNo = dto.getMarketShipmentNo();
		if (shipmentNo == null || shipmentNo.isBlank()) {
			throw new IllegalArgumentException(
				"배송 식별자 없이 배송을 만들 수 없습니다: orderId=" + orderId);
		}

		boolean meaningful = ShippingData.isMeaningfulTracking(dto.getTrackingNo());
		String trackingNo = meaningful ? dto.getTrackingNo() : null;
		Boolean ownedByMarket = ShippingData.marketOwnsTracking(dto.getTrackingNo());

		Shipment shipment = shipmentRepository
			.findByOrderIdAndMarketShipmentNo(orderId, shipmentNo)
			.orElseGet(() -> Shipment.builder()
				.orderId(orderId)
				.marketShipmentNo(shipmentNo)
				.build());

		shipment.applyMarketTracking(trackingNo);

		if (!shipment.hasOwnTracking()) {
			shipment.applyTracking(trackingNo, meaningful ? dto.getCarrier() : null, ownedByMarket);
			if (meaningful) {
				shipment.applyTrackingSource(TrackingSource.MARKET);
			}
		}
		shipment.applyDeliveryStatus(dto.getDeliveryStatus());
		shipment.applyShippedAt(dto.getShippedAt());

		return shipmentRepository.save(shipment);
	}

	@Transactional
	public void linkToShipment(OrderLineItem item, Shipment shipment) {
		item.assignShipmentId(shipment.getId());
		ShippingData current = item.getShippingData() != null
			? item.getShippingData()
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
		item.applyShippingData(mirrored.build());
		orderLineItemRepository.save(item);
	}
}

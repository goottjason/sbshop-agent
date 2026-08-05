package com.sbshop.agent.core.application.order.service;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.RequiredArgsConstructor;

/**
 * 배송을 <b>마켓 배송식별자로</b> 찾아 upsert하고, 라인아이템을 그 배송에 연결한다.
 *
 * <p>배열 순서에 기대지 않는 것이 핵심이다. Cafe24 현행 방식은 items 배열 인덱스로
 * 라인아이템을 짝짓는데, 마켓이 순서를 바꾸면 엉뚱한 상품에 송장이 붙는다.
 *
 * <p>라인아이템 생성은 여기서 하지 않는다 — 상품 매핑(sbCode 조회)과 정산액 계산이
 * 마켓 고유 로직이라 각 동기화 서비스에 남는다. 이 서비스는 배송 계층과 연결만 책임진다.
 */
@Service
@RequiredArgsConstructor
public class OrderShipmentUpsertService {

	private final ShipmentRepository shipmentRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	public Shipment upsertShipment(Long orderId, MarketShipmentDto dto) {
		String shipmentNo = dto.getMarketShipmentNo();
		if (shipmentNo == null || shipmentNo.isBlank()) {
			throw new IllegalArgumentException(
				"배송 식별자 없이 배송을 만들 수 없습니다: orderId=" + orderId);
		}

		// D-119/D-120: 마켓의 자리표시자·빈 값이 실송장을 덮지 않도록 실값일 때만 반영한다.
		boolean meaningful = ShippingData.isMeaningfulTracking(dto.getTrackingNo());
		String trackingNo = meaningful ? dto.getTrackingNo() : null;
		// D-129: 마켓이 실송장을 알려줬다 = 마켓이 그 송장을 보유한다.
		Boolean ownedByMarket = ShippingData.marketOwnsTracking(dto.getTrackingNo());

		Shipment shipment = shipmentRepository
			.findByOrderIdAndMarketShipmentNo(orderId, shipmentNo)
			.orElseGet(() -> Shipment.builder()
				.orderId(orderId)
				.marketShipmentNo(shipmentNo)
				.build());

		shipment.applyTracking(trackingNo, meaningful ? dto.getCarrier() : null, ownedByMarket);
		shipment.applyDeliveryStatus(dto.getDeliveryStatus());
		shipment.applyShippedAt(dto.getShippedAt());

		return shipmentRepository.save(shipment);
	}

	/**
	 * 라인아이템을 배송에 연결하고 송장 정보를 <b>미러로</b> 내려쓴다(설계 4.4).
	 *
	 * <p>라인아이템의 송장 컬럼은 기존 그리드·엑셀·정산 쿼리·이메일 파이프라인이 전부
	 * 읽는다. 한 번에 다 옮기면 검증 범위가 통제 불가능해지므로 당분간 복제를 유지한다.
	 * <b>쓰기의 단일 원본은 배송이다</b> — 소비처를 모두 옮긴 뒤 미러 컬럼을 제거한다.
	 *
	 * <p>진행상태는 건드리지 않는다. 같은 배송이라도 상품주문마다 상태가 갈린다.
	 */
	public void linkToShipment(OrderLineItem item, Shipment shipment) {
		item.assignShipmentId(shipment.getId());
		item.applyShippingData(item.getShippingData().toBuilder()
			.trackingNo(shipment.getTrackingNo())
			.shippingCarrier(shipment.getShippingCarrier())
			.trackingSentToMarket(shipment.getTrackingSentToMarket())
			.build());
		orderLineItemRepository.save(item);
	}
}

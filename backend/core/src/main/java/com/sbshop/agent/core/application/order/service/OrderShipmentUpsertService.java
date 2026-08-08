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

	@Transactional
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

		// 마켓이 알고 있는 값은 언제나 기록한다 — 이 값과 실제 송장의 차이가 곧 "마켓 미반영"이다.
		shipment.applyMarketTracking(trackingNo);

		// <b>실제 송장은 마켓 값이 덮지 않는다.</b> 우리가 이미 아는 송장(이메일·수동 입력)이 있으면
		// 그것이 진실이고, 마켓 값은 "마켓이 아직 모른다"는 표시일 뿐이다. 2026-08-07 실측:
		// 이메일 교정 11:34 → 동기화 원복 11:38. 되돌아가면 배지도 꺼져 고칠 일이 있다는 사실이
		// 화면에서 사라지고, 화면·엑셀·고객 응대가 가송장을 진짜처럼 안내한다.
		// 우리가 송장을 모를 때만 마켓 값을 채택한다(마켓이 유일한 출처인 정상 경로).
		if (!shipment.hasOwnTracking()) {
			shipment.applyTracking(trackingNo, meaningful ? dto.getCarrier() : null, ownedByMarket);
			// 마켓 값을 채택한 경우에만 출처가 마켓이다. 우리 송장이 있으면 채택하지 않으므로 출처도 그대로다.
			if (meaningful) {
				shipment.applyTrackingSource(TrackingSource.MARKET);
			}
		}
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
	 *
	 * <p>배송의 세 필드(송장·택배사·마켓전송여부)는 {@code Shipment.applyTracking}과 같은
	 * "null = 판단 없음, 기존 값 유지" 규칙으로 미러링한다. 배송이 아직 송장을 못 받은
	 * 상태(null)로 이 메서드가 불리면, 이메일 파이프라인 등이 라인아이템에 먼저 채워둔
	 * 실송장을 지우지 않는다(D-125와 같은 시나리오). 단, 이 규칙은 배송 쪽이 송장을
	 * "명시적으로 지우고 싶을 때" 표현할 방법이 없다는 트레이드오프가 있다 — 현재는 그런
	 * 요구가 없어 문제 없지만, 필요해지면 별도 시그널(예: 지움 전용 커맨드)이 필요하다.
	 */
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

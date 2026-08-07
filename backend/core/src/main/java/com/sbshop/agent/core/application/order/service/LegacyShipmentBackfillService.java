package com.sbshop.agent.core.application.order.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 6단계 전제: <b>배송에 속하지 않은 라인아이템</b>을 없앤다(일회성 백필, 멱등).
 *
 * <p>3계층 전환은 각 마켓의 조회 창(30일) 안에서만 일어난다. 창 밖의 옛 주문은 마켓이 더는
 * 변경으로 내려주지 않아 동기화가 영원히 만나지 못하고, 그 라인아이템은 {@code shipment_id}가
 * 비어 있다(2026-08-06 실측 127건 — 쿠팡 109 · N스토어 11 · 11번가 5 · G마켓 2).
 *
 * <p>미러 컬럼과 {@code sb_order.shipment_box_id}를 제거하려면 <b>모든 라인아이템이 배송을
 * 가져야</b> 한다. 그 전에 제거하면 옛 행의 송장이 갈 곳을 잃고, 쿠팡 송장 수정은 박스 식별자를
 * 잃는다.
 *
 * <p><b>원본이 뒤집히는 지점이다.</b> 종전에는 라인아이템이 송장의 원본이고 배송이 미러였다.
 * 여기서 라인아이템의 값을 배송으로 <b>승격</b>시킨다. 미러 컬럼은 그대로 둔다 — 소비처
 * (그리드·엑셀·정산·이메일)를 아직 옮기지 않았다.
 */
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
				// 주문 없는 라인아이템은 우리가 만들 수 있는 배송이 없다. 지우지 않고 남긴다.
				log.warn("[배송백필] 주문 없는 라인아이템 건너뜀: lineItemId={}, orderId={}",
					item.getId(), item.getOrderId());
				skipped++;
				continue;
			}
			String shipmentNo = resolveShipmentNo(order);
			if (shipmentNo == null || shipmentNo.isBlank()) {
				log.warn("[배송백필] 배송 식별자를 만들 수 없어 건너뜀: orderId={}", order.getId());
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
				// 라인아이템 값을 배송으로 승격한다. null은 "판단 없음"이라 기존 값을 덮지 않는다.
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

	/**
	 * 이 주문의 배송 식별자. 쿠팡은 {@code shipment_box_id}가 곧 배송 식별자다(송장 수정 API가
	 * 이 값을 요구한다). 나머지 마켓은 레거시 행에 배송 식별자가 남아 있지 않으므로 주문번호로
	 * 대체한다 — 정규화기와 같은 규칙이다(설계 §3.3). 주문당 배송 1건이므로 유니크와 충돌하지 않는다.
	 */
	private String resolveShipmentNo(Order order) {
		String boxId = order.getShipmentBoxId();
		if (boxId != null && !boxId.isBlank()) {
			return boxId;
		}
		return order.getMarketOrderNo();
	}
}

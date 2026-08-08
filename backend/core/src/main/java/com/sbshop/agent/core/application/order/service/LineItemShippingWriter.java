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

/**
 * 라인아이템 송장을 쓰는 <b>단 하나의 통로</b>. 배송이 붙어 있으면 배송에 함께 쓴다 (D-133).
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>설계 §4.4는 "쓰기는 배송이 단일 원본이고, 라인아이템 컬럼에 직접 쓰는 코드는 남기지
 * 않는다"고 정했다. 그러나 실제로는 이메일 파이프라인(iHerb 발송메일)·수동 송장 입력·
 * 발송 처리기가 각자 라인아이템에 직접 쓰고 있었다. 1단계에서 그 전제가 <b>거짓인 채로</b>
 * 미러링만 들어갔다.
 *
 * <p>2단계에서 발송처리 호출 단위가 <b>배송</b>으로 바뀌면(설계 §6.1) 배송이 모르는 송장은
 * 마켓에 나가지 않는다. 즉 이메일이 라인아이템에만 써 둔 송장은 조용히 사라진다 — iHerb
 * 발송메일 → 쿠팡·11번가 송장 자동반영이라는 <b>지금 라이브인 파이프라인</b>이 멈춘다.
 * D-129의 "마켓 미반영" 배지도 배송 플래그를 보게 되므로 영구히 미반영으로 남는다.
 *
 * <h2>지금은 동작이 바뀌지 않는다</h2>
 *
 * <p>운영 DB의 라인아이템 240행 전부가 {@code shipment_id}가 NULL이다(2026-08-06 실측).
 * 배송이 없으면 이 통로는 종전과 완전히 같게 동작하고 배송 계층을 조회조차 하지 않는다.
 * 2단계가 배송을 채우기 시작하면 같은 코드가 자동으로 올바르게 동작한다.
 *
 * <h2>무엇을 올리고 무엇을 올리지 않는가</h2>
 *
 * <p>송장·택배사·마켓보유여부는 배송으로 올린다. <b>진행상태는 올리지 않는다</b> — 같은
 * 배송이라도 상품주문마다 갈린다(설계 §3.2, 정나영 건 순번1 결제완료 / 순번2 발송완료).
 *
 * <p>한 배송은 한 송장이므로(§3.1) 같은 배송의 <b>형제 라인아이템에도 미러</b>한다. 11번가는
 * 묶음배송번호가 같으면 한 번의 발송처리로 나머지 주문번호까지 모두 발송 처리된다(-3308 문서).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineItemShippingWriter {

	private final ShipmentRepository shipmentRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	/**
	 * 라인아이템의 배송정보를 갱신하고, 배송이 붙어 있으면 배송에도 송장을 쓴다.
	 *
	 * <p>{@code data}는 호출자가 기존 값 위에 이미 합성해 넘긴 완성된 상태다(종전 계약 그대로).
	 * 배송으로 올릴 때만 {@code null = 판단 없음}으로 취급해 기존 값을 지키지 않는다.
	 */
	@Transactional
	public void applyShipping(OrderLineItem item, ShippingData data) {
		applyShipping(item, data, null);
	}

	/**
	 * @param source 이 쓰기가 확인한 출처. {@code null}이면 출처를 건드리지 않는다
	 *               (출처를 판단할 수 없는 호출자를 위한 경로).
	 */
	@Transactional
	public void applyShipping(OrderLineItem item, ShippingData data, TrackingSource source) {
		item.applyShippingData(data);
		orderLineItemRepository.save(item);
		writeThrough(item, data.getTrackingNo(), data.getShippingCarrier(),
			data.getTrackingSentToMarket(), source);
	}

	/**
	 * 마켓이 <b>이 송장을 정말 갖고 있는가</b> — {@code trackingSentToMarket} 플래그가 아니라
	 * 배송에 기록된 <b>마켓 보유 송장</b>으로 판정한다(D-147).
	 *
	 * <p>그 플래그는 전송이 실패해도 참으로 남을 수 있다. 2026-08-07 라이브에서 거짓 성공(D-145)이
	 * 플래그를 참으로 찍어 놓았고, 이메일 파이프라인이 그것을 믿고 <b>전송을 아예 시도하지 않았다.</b>
	 * 시도하지 않으니 마켓의 영구 거부를 만나지 못하고, 화면은 "곧 자동 반영됨"이라고 잘못 안내했다.
	 *
	 * <p>마켓 보유값을 아직 모르면(동기화 전) 종전 플래그로 폴백한다 — 방금 보낸 송장을 매 사이클
	 * 다시 보내지 않기 위해서다.
	 */
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

	/**
	 * 값은 그대로 두고 <b>출처만</b> 이메일로 올린다. 이메일이 같은 값으로 확인해 준 경우다.
	 */
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

	/** 사람이 판매자센터에서 고쳐 주기를 기다리는 중인가 — 그동안 재전송은 무의미하다. */
	@Transactional(readOnly = true)
	public boolean isAwaitingManualFix(OrderLineItem item) {
		if (item.getShipmentId() == null) {
			return false;
		}
		return shipmentRepository.findById(item.getShipmentId())
			.map(Shipment::isManualFixRequired)
			.orElse(false);
	}

	/**
	 * 마켓이 <b>영구 거부</b>해 사람이 판매자센터에서 직접 고쳐야 함을 배송에 표시한다.
	 *
	 * <p>재시도로는 해결되지 않는 거부(네이버 배송중 송장 수정 등)에서만 부른다. 사람이 고쳐
	 * 마켓 값이 우리 송장과 같아지면 {@code applyMarketTracking}이 이 표시를 스스로 끈다.
	 */
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

	/** 마켓 전송 완료를 마킹하고 배송에도 반영한다. */
	@Transactional
	public void markTrackingAsSent(OrderLineItem item) {
		item.markTrackingAsSent();
		orderLineItemRepository.save(item);
		writeThrough(item, null, null, Boolean.TRUE, null);
	}

	/**
	 * 배송에 쓰고 같은 배송의 형제 라인아이템에 미러한다.
	 *
	 * <p>배송을 찾지 못하면 경고만 남기고 넘어간다. 송장은 부수 경로의 성공 여부와 무관하게
	 * 실재하는 사실이므로, 미러 실패가 라인아이템 기록을 되돌릴 근거가 되지 못한다(D-125).
	 */
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

	/**
	 * 같은 배송의 다른 라인아이템에 송장을 내려쓴다. 진행상태는 건드리지 않는다.
	 *
	 * <p>형제 조회 키는 라인아이템이 들고 있는 {@code shipment_id}를 쓴다 — 배송 엔티티의
	 * id에 기대면 아직 flush 전인 신규 배송에서 null로 조회하게 된다.
	 */
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

	/**
	 * 방금 쓴 그 행인지 판정한다. 영속 컨텍스트가 같은 인스턴스를 돌려주는 것이 보통이지만,
	 * 그것에만 기대면 아직 id가 없는 행(테스트·신규 생성 직후)에서 자신을 형제로 착각한다.
	 */
	private static boolean isSameRow(OrderLineItem a, OrderLineItem b) {
		if (a == b) {
			return true;
		}
		return a.getId() != null && a.getId().equals(b.getId());
	}
}

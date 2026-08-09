package com.sbshop.agent.core.application.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 3계층 DTO(주문 / 배송 / 상품주문)를 DB에 반영하는 <b>공통 골격</b>.
 *
 * <p>11번가(D-134)와 쿠팡(D-137)을 전환하며 이 로직이 거의 그대로 두 번 복제됐다. 차이는
 * 로그 태그·상품 해석·정산액 산출뿐이어서 {@link MarketLineItemSyncPolicy}로 뺐다. 복제를 그대로
 * 두면 <b>같은 버그를 마켓 수만큼 고쳐야 한다</b> — Cafe24·N스토어가 남아 있으므로 지금 합친다.
 *
 * <p>골격이 소유하는 규율(마켓과 무관하게 같아야 하는 것):
 * <ul>
 * <li><b>매칭은 주문 전체에서 한 번.</b> 배송별로 나눠 매칭하면 상품주문이 다른 배송으로 옮겨갔을 때
 *     같은 기존 행을 두 배송이 각각 채택해 중복이 생긴다.
 * <li><b>상품 해석은 상품주문당 한 번.</b> 두 번 부르면 쿠팡의 식별자 보강처럼 부수효과가 있는
 *     경로가 중복 실행된다.
 * <li><b>송장은 여기서 직접 쓰지 않는다.</b> 배송이 단일 원본이고 {@code linkToShipment}가
 *     내려쓴다(설계 4.4, D-133).
 * <li><b>{@code UNKNOWN} 상태는 덮지 않는다.</b> 새 상태값이 등장했을 때 배송중 주문이 신규로
 *     되돌아가는 것이 가장 나쁜 실패다.
 * <li><b>분할 보류.</b> 상품을 식별할 수 없는 상품주문이 있는데 짝짓지 못한 기존 행이 남으면
 *     새 행을 만들지 않는다 — 빈 껍데기 행과 고아 행을 동시에 만드는 조합이다(D-136).
 * <li><b>기존 행은 지우지 않는다.</b> 마켓이 더는 보내지 않아도 우리 고유 정보가 붙어 있을 수 있다.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLineItemSyncDispatcher {

	private final OrderLineItemRepository orderLineItemRepository;
	private final OrderShipmentUpsertService orderShipmentUpsertService;

	/**
	 * 배송을 upsert하고 상품주문마다 라인아이템을 맞춰 넣는다.
	 *
	 * @param existing 이 주문의 기존 라인아이템(신규 주문이면 빈 목록)
	 */
	public void sync(Order order, MarketOrderDto dto, List<OrderLineItem> existing,
		MarketLineItemSyncPolicy policy) {
		String tag = policy.logTag();
		List<MarketShipmentDto> shipmentDtos = dto.getShipments();
		if (shipmentDtos == null || shipmentDtos.isEmpty()) {
			log.warn("[{}] 배송 계층이 없는 DTO — 건너뜀: orderNo={}", tag, dto.getMarketOrderNo());
			return;
		}

		// 상품주문 → 소속 배송 역참조. 매칭 결과에서 배송을 되찾으려면 필요하다.
		IdentityHashMap<MarketLineItemDto, MarketShipmentDto> owner = new IdentityHashMap<>();
		IdentityHashMap<MarketLineItemDto, Long> resolvedProducts = new IdentityHashMap<>();
		List<OrderLineItemMatcher.Incoming> incoming = new ArrayList<>();
		for (MarketShipmentDto shipmentDto : shipmentDtos) {
			if (shipmentDto.getLineItems() == null) {
				continue;
			}
			for (MarketLineItemDto lineItemDto : shipmentDto.getLineItems()) {
				owner.put(lineItemDto, shipmentDto);
				Long productId = policy.resolveProductId(lineItemDto);
				resolvedProducts.put(lineItemDto, productId);
				incoming.add(new OrderLineItemMatcher.Incoming(lineItemDto, productId));
			}
		}

		OrderLineItemMatcher.MatchResult match = OrderLineItemMatcher.matchAndAdopt(existing, incoming);
		for (String warning : match.warnings()) {
			log.warn("[{}] orderNo={} {}", tag, dto.getMarketOrderNo(), warning);
		}

		// 배송 upsert는 배송식별자당 한 번만. 여러 상품주문이 같은 배송을 가리킨다.
		IdentityHashMap<MarketShipmentDto, Shipment> shipments = new IdentityHashMap<>();
		for (MarketShipmentDto shipmentDto : shipmentDtos) {
			shipments.put(shipmentDto, orderShipmentUpsertService.upsertShipment(order.getId(), shipmentDto));
		}

		for (OrderLineItemMatcher.Adoption adoption : match.matched()) {
			OrderLineItem item = adoption.lineItem();
			applyLineItem(item, adoption.dto(), resolvedProducts.get(adoption.dto()));
			recoverSettlementIfZeroed(tag, dto, item, adoption.dto(), policy);
			orderLineItemRepository.save(item);
			orderShipmentUpsertService.linkToShipment(item, shipments.get(owner.get(adoption.dto())));
		}

		if (shouldDeferSplit(match, incoming)) {
			// 상품을 식별할 수 없는 상품주문이 있는데 짝짓지 못한 기존 행이 남았다. 새 행을 만들면
			// (a) 상품·금액이 빈 껍데기 행이 생기고 (b) 소싱처·구매상태가 붙은 옛 행이 고아가 된다.
			// 라이브에서 정나영 건이 정확히 이렇게 됐다(2026-08-06, D-136).
			// 분할을 미룬다 — 상품 신호를 얻으면 그때 정확히 갈린다.
			log.warn("[{}] ⚠ 분할 보류: orderNo={} 상품주문 {}건을 만들지 않았다 — 상품을 식별할 수 없고"
				+ " 짝짓지 못한 기존 행이 있다(id={}). 기존 행에 상품주문번호를 직접 지정하면"
				+ " 다음 동기화에서 정확히 갈린다.",
				tag, dto.getMarketOrderNo(), match.toCreate().size(),
				match.unclaimed().stream().map(OrderLineItem::getId).toList());
			return;
		}

		for (MarketLineItemDto lineItemDto : match.toCreate()) {
			OrderLineItem created = policy.createLineItem(
				lineItemDto, order.getId(), resolvedProducts.get(lineItemDto));
			orderLineItemRepository.save(created);
			orderShipmentUpsertService.linkToShipment(created, shipments.get(owner.get(lineItemDto)));
			log.info("[{}] 상품주문 신규 라인아이템 생성: orderNo={}, key={}",
				tag, dto.getMarketOrderNo(), lineItemDto.getMarketLineItemNo());
		}

		warnUnclaimed(tag, dto, match);
	}

	/**
	 * 상품주문 값을 라인아이템에 반영한다.
	 *
	 * <p>송장·택배사는 <b>건드리지 않는다</b> — 배송이 단일 원본이고 미러가 내려쓴다(D-133).
	 * 상태가 {@code UNKNOWN}이면 덮지 않는다.
	 */
	private void applyLineItem(OrderLineItem item, MarketLineItemDto dto, Long productId) {
		if (productId != null && !productId.equals(item.getProductId())) {
			item.assignProductId(productId);
		}
		ShippingStatus status = dto.getStatus();
		if (status == null || status == ShippingStatus.UNKNOWN) {
			return;
		}
		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.shippingStatus(status)
			.build();
		item.applyShippingData(cmd.toShippingData(item.getShippingData()));
	}

	/**
	 * 정산액이 <b>거짓으로 0이 된 행</b>을 마켓 값으로 되살린다 (D-160).
	 *
	 * <p>2026-08-08 라이브 사고: 백필이 최근 주문을 거짓 취소했고(조회 구간 밖을 판정했다),
	 * 뒤이어 D-098 정규화가 정산액을 0+검증됨으로 내렸다. 다음 동기화가 <b>배송상태는 복원했지만</b>
	 * 정산액을 되돌리는 주체가 없어 0이 영구히 남았다 — 종결 전 쿠팡 주문 5건이 그렇게 손상됐다.
	 *
	 * <p>규율은 한 문장이다: <b>정산 0은 환불일 때만 정당하다.</b> 환불성 종결이 아닌데 0이면
	 * 그건 사실이 아니라 손상이므로 되살린다. 이미 값이 있는 행은 건드리지 않는다(멱등) —
	 * 이미 대조·정산한 과거 수치를 동기화가 흔들면 안 된다. 마켓이 금액을 주지 않으면 그대로 둔다.
	 */
	private void recoverSettlementIfZeroed(String tag, MarketOrderDto orderDto, OrderLineItem item,
		MarketLineItemDto lineItemDto, MarketLineItemSyncPolicy policy) {
		ShippingStatus status = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (status == null || status.isRefundTerminal()) {
			return;
		}
		BigDecimal current = item.getSettlementData() != null
			? item.getSettlementData().getSettlementAmount() : null;
		if (current != null && current.signum() != 0) {
			return;
		}
		BigDecimal recovered = policy.settlementAmount(lineItemDto);
		if (recovered == null || recovered.signum() == 0) {
			return;
		}
		item.recoverSettlement(recovered);
		log.info("[{}] 정산액 복구: orderNo={}, key={}, {} → {} (종결 전인데 0이었다)",
			tag, orderDto.getMarketOrderNo(), item.getMarketLineItemNo(), current, recovered);
	}

	/**
	 * 분할을 미뤄야 하는가 — <b>빈 껍데기 행과 고아 행을 동시에 만드는 조합</b>을 막는다.
	 *
	 * <p>조건 세 개가 겹칠 때만 참이다: 새로 만들 상품주문이 있고 · 짝짓지 못한 기존 행이 있고 ·
	 * 새로 만들 것 중 상품을 식별할 수 없는 것이 있다.
	 *
	 * <p>새로 들어오는 주문은 신규 상태 목록을 반드시 거치므로 상품 정보를 갖는다. 즉 이 가드는
	 * <b>기능이 켜지기 전에 이미 조회 창을 지나 있던 주문</b>에만 걸린다.
	 */
	private boolean shouldDeferSplit(OrderLineItemMatcher.MatchResult match,
		List<OrderLineItemMatcher.Incoming> incoming) {
		if (match.toCreate().isEmpty() || match.unclaimed().isEmpty()) {
			return false;
		}
		return match.toCreate().stream().anyMatch(create -> incoming.stream()
			.anyMatch(in -> in.dto() == create && in.resolvedProductId() == null));
	}

	private void warnUnclaimed(String tag, MarketOrderDto dto, OrderLineItemMatcher.MatchResult match) {
		if (match.unclaimed().isEmpty()) {
			return;
		}
		if (!match.toCreate().isEmpty()) {
			// 이 조합이 사람의 확인을 요구한다: 새 행을 만들면서 옛 행을 짝짓지 못했다는 뜻이므로,
			// 소싱처·실구매가·구매상태가 붙은 행이 고아로 남았을 수 있다.
			log.warn("[{}] ⚠ 확인 필요: orderNo={} 신규 {}건을 만들면서 기존 {}건을 짝짓지 못했다"
				+ " — 구매정보가 옛 행에 남아 있을 수 있다(라인아이템 id={})",
				tag, dto.getMarketOrderNo(), match.toCreate().size(), match.unclaimed().size(),
				match.unclaimed().stream().map(OrderLineItem::getId).toList());
			return;
		}
		log.warn("[{}] orderNo={} 마켓이 더는 보내지 않는 라인아이템 {}건 — 지우지 않고 남긴다",
			tag, dto.getMarketOrderNo(), match.unclaimed().size());
	}
}

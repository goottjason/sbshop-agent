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

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLineItemSyncDispatcher {
	private final OrderLineItemRepository orderLineItemRepository;
	private final OrderShipmentUpsertService orderShipmentUpsertService;
	private final TrackingMismatchResolver trackingMismatchResolver;

	public void sync(Order order, MarketOrderDto dto, List<OrderLineItem> existing,
		MarketLineItemSyncPolicy policy) {
		String tag = policy.logTag();
		List<MarketShipmentDto> shipmentDtos = dto.getShipments();
		if (shipmentDtos == null || shipmentDtos.isEmpty()) {
			log.warn("[{}] 배송 계층이 없는 DTO — 건너뜀: orderNo={}", tag, dto.getMarketOrderNo());
			return;
		}

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

		IdentityHashMap<MarketShipmentDto, Shipment> shipments = new IdentityHashMap<>();
		for (MarketShipmentDto shipmentDto : shipmentDtos) {
			Shipment shipment = orderShipmentUpsertService.upsertShipment(order.getId(), shipmentDto);
			shipments.put(shipmentDto, shipment);
			trackingMismatchResolver.resolve(order.getMarketType(), shipment);
		}

		for (OrderLineItemMatcher.Adoption adoption : match.matched()) {
			OrderLineItem item = adoption.lineItem();
			applyLineItem(item, adoption.dto(), resolvedProducts.get(adoption.dto()));
			recoverSettlementIfZeroed(tag, dto, item, adoption.dto(), policy);
			orderLineItemRepository.save(item);
			orderShipmentUpsertService.linkToShipment(item, shipments.get(owner.get(adoption.dto())));
			warnUnmapped(tag, dto, item, adoption.dto());
		}

		if (shouldDeferSplit(match, incoming)) {
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
			warnUnmapped(tag, dto, created, lineItemDto);
		}

		warnUnclaimed(tag, dto, match);
	}

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

	private void recoverSettlementIfZeroed(String tag, MarketOrderDto orderDto, OrderLineItem item,
		MarketLineItemDto lineItemDto, MarketLineItemSyncPolicy policy) {
		ShippingStatus status = item.getShippingData() != null
			? item.getShippingData().getShippingStatus() : null;
		if (status == null || RefundTerminalPolicy.isRefundTerminal(item)) {
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

	private boolean shouldDeferSplit(OrderLineItemMatcher.MatchResult match,
		List<OrderLineItemMatcher.Incoming> incoming) {
		if (match.toCreate().isEmpty() || match.unclaimed().isEmpty()) {
			return false;
		}
		return match.toCreate().stream().anyMatch(create -> incoming.stream()
			.anyMatch(in -> in.dto() == create && in.resolvedProductId() == null));
	}

	private void warnUnmapped(String tag, MarketOrderDto orderDto, OrderLineItem item,
		MarketLineItemDto lineItemDto) {
		if (item.getProductId() != null) {
			return;
		}
		log.warn("[{}] ⚠ 상품 미매핑: orderNo={}, key={}, 상품명={}, 마켓식별자={}"
			+ " — 라인아이템(id={})에 상품이 비어 있다. 재고·정산·소싱이 이 주문을 건너뛴다.",
			tag, orderDto.getMarketOrderNo(), lineItemDto.getMarketLineItemNo(),
			lineItemDto.getProductName(), lineItemDto.getMarketSpecificData(), item.getId());
	}

	private void warnUnclaimed(String tag, MarketOrderDto dto, OrderLineItemMatcher.MatchResult match) {
		if (match.unclaimed().isEmpty()) {
			return;
		}
		if (!match.toCreate().isEmpty()) {
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

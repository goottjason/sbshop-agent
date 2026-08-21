package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderShipProcessor {
	private final OrderRepository orderRepository;
	private final MarketCredentialRepository credentialRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketplaceShippingService marketplaceShippingService;
	private final LineItemShippingWriter shippingWriter;

	@Transactional
	public OrderShipOutcome shipSingleOrder(Long orderId) {
		Order order = orderRepository.findById(orderId).orElse(null);
		if (order == null) {
			return OrderShipOutcome.failed("주문 " + orderId + ": 주문 없음");
		}

		MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
		if (cred == null) {
			log.warn("마켓 유형 {}에 대한 인증 정보가 없습니다.", order.getMarketType());
			return OrderShipOutcome.failed("주문 " + orderId + ": 마켓 인증정보 없음(" + order.getMarketType() + ")");
		}

		List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(orderId);
		boolean orderShipped = false;
		boolean orderFailed = false;
		boolean anyProcessable = false;
		String firstError = null;

		for (OrderLineItem item : lineItems) {
			String trackingNo = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
			if (trackingNo == null || trackingNo.isEmpty()) {
				continue;
			}

			ShippingStatus status = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			if (status == ShippingStatus.DISPATCHED || status == ShippingStatus.SHIPPED
				|| status == ShippingStatus.DELIVERED || status == ShippingStatus.CANCELED
				|| status == ShippingStatus.RETURNED || status == ShippingStatus.EXCHANGED) {
				log.info("라인아이템 {} 스킵 — 이미 {} 상태(재발송 대상 아님)", item.getId(), status);
				continue;
			}

			anyProcessable = true;
			ShippingCarrier carrier = item.getShippingData() != null
				? item.getShippingData().getShippingCarrier() : null;

			try {
				marketplaceShippingService.getPort(order.getMarketType())
					.shipOrder(cred, order, item, trackingNo, carrier);

				ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
					.trackingNo(trackingNo)
					.shippingStatus(ShippingStatus.DISPATCHED)
					.build();
				shippingWriter.applyShipping(item, cmd.toShippingData(item.getShippingData()), TrackingSource.MANUAL);
				orderShipped = true;
			} catch (Exception e) {
				log.error("라인아이템 {} 배송 처리 실패: {}", item.getId(), e.getMessage());
				orderFailed = true;
				if (firstError == null) {
					firstError = e.getMessage();
				}
			}
		}

		if (orderFailed) {
			return OrderShipOutcome.failed(
				"주문 " + orderId + ": " + (firstError != null ? firstError : "발송 실패"));
		} else if (orderShipped) {
			return OrderShipOutcome.shipped();
		} else if (!anyProcessable) {
			return OrderShipOutcome.skipped();
		}
		return OrderShipOutcome.skipped();
	}
}

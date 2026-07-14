package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sbshop.agent.core.domain.order.enums.ShippingStatus.SHIPPED;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderShipService {

	private final OrderRepository orderRepository;
	private final MarketCredentialRepository credentialRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketplaceShippingService marketplaceShippingService;

	@Transactional
	public List<Order> bulkShipOrders(List<Long> orderIds) {
		List<Order> shippedOrders = new ArrayList<>();

		for (Long orderId : orderIds) {
			Order order = orderRepository.findById(orderId).orElse(null);
			if (order == null)
				continue;

			MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
			if (cred == null) {
				log.warn("마켓 유형 {}에 대한 인증 정보가 없습니다.", order.getMarketType());
				continue;
			}

			List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(orderId);
			boolean orderShipped = false;

			for (OrderLineItem item : lineItems) {
				String trackingNo = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
				if (trackingNo == null || trackingNo.isEmpty())
					continue;

				// 이미 발송(SHIPPED)·배송완료(DELIVERED)·종료(취소/반품/교환) 상태면 재발송하지 않는다(F-ORD-29).
				ShippingStatus status = item.getShippingData() != null
					? item.getShippingData().getShippingStatus() : null;
				if (status == ShippingStatus.SHIPPED || status == ShippingStatus.DELIVERED
					|| status == ShippingStatus.CANCELED || status == ShippingStatus.RETURNED
					|| status == ShippingStatus.EXCHANGED) {
					log.info("라인아이템 {} 스킵 — 이미 {} 상태(재발송 대상 아님)", item.getId(), status);
					continue;
				}

				ShippingCarrier carrier = item.getShippingData() != null
					? item.getShippingData().getShippingCarrier() : null;

				try {
					marketplaceShippingService.getPort(order.getMarketType())
						.shipOrder(cred, order, item, trackingNo, carrier);

					ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
						.trackingNo(trackingNo)
						.shippingStatus(SHIPPED)
						.build();
					item.applyShippingData(cmd.toShippingData(item.getShippingData()));
					calculateSettlement(item);
					orderLineItemRepository.save(item);
					orderShipped = true;
				} catch (Exception e) {
					log.error("라인아이템 {} 배송 처리 실패: {}", item.getId(), e.getMessage());
				}
			}
			if (orderShipped)
				shippedOrders.add(order);
		}
		return shippedOrders;
	}

	static void calculateSettlement(OrderLineItem item) {
		if (item.getSettlementData() != null && item.getSettlementData().getSettlementAmount() != null) {
			BigDecimal currentSettlement = item.getSettlementData().getSettlementAmount();
			BigDecimal settlementAmount = currentSettlement.multiply(new BigDecimal("0.89"));
			item.applySettlement(settlementAmount);
		}
	}
}

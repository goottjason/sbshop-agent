package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
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
	private final List<MarketOrderPort> marketOrderPorts;

	/**
	 * MarketType에 해당하는 MarketOrderPort를 찾는 헬퍼 메서드
	 */
	private MarketOrderPort getPort(MarketType marketType) {
		return marketOrderPorts.stream()
			.filter(port -> port.getMarketType() == marketType)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"지원하지 않는 마켓: " + marketType));
	}

	@Transactional
	public int bulkShipOrders(List<Long> orderIds) {
		int shippedCount = 0;

		for (Long orderId : orderIds) {
			Order order = orderRepository.findById(orderId).orElse(null);
			if (order == null)
				continue;

			MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
			if (cred == null) {
				log.warn("No credentials for market type: {}", order.getMarketType());
				continue;
			}

			List<OrderLineItem> lineItems = orderLineItemRepository.findByOrderId(orderId);
			boolean orderShipped = false;

			for (OrderLineItem item : lineItems) {
				String trackingNo = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
				if (trackingNo == null || trackingNo.isEmpty())
					continue;

				ShippingCarrier carrier = item.getShippingData() != null
					? item.getShippingData().getShippingCarrier() : null;

				try {
					MarketOrderPort port = getPort(order.getMarketType());
					port.shipOrder(cred, order, item, trackingNo, carrier);

					item.updateShipping(trackingNo, SHIPPED,
						item.getShippingData() != null ? item.getShippingData().getIsUnipassDone() : null);
					calculateSettlement(item);
					orderLineItemRepository.save(item);
					orderShipped = true;
				} catch (Exception e) {
					log.error("Failed to ship orderLineItem {}: {}", item.getId(), e.getMessage());
				}
			}
			if (orderShipped)
				shippedCount++;
		}
		return shippedCount;
	}

	static void calculateSettlement(OrderLineItem item) {
		if (item.getSettlementData() != null && item.getSettlementData().getSalePrice() != null) {
			BigDecimal salePrice = item.getSettlementData().getSalePrice();
			BigDecimal settlementAmount = salePrice.multiply(new BigDecimal("0.89"));

			BigDecimal netProfit = null;
			if (item.getSourcingData() != null && item.getSourcingData().getSourcingAmount() != null) {
				netProfit = settlementAmount.subtract(item.getSourcingData().getSourcingAmount());
			}

			item.updateSettlement(salePrice, settlementAmount, netProfit);
		}
	}
}

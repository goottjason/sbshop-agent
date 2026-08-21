package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TerminalSettlementService {
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	@Transactional
	public int zeroSettlementForRefunded(MarketType marketType) {
		int count = 0;
		for (Order order : orderRepository.findByMarketType(marketType)) {
			for (OrderLineItem item : orderLineItemRepository.findByOrderId(order.getId())) {
				if (shouldZero(item)) {
					item.applySettlement(BigDecimal.ZERO);
					item.markSettlementVerified();
					orderLineItemRepository.save(item);
					count++;
				}
			}
		}
		if (count > 0) {
			log.info("[{}] 환불성 종결 정산0 정규화: {}건", marketType, count);
		}
		return count;
	}

	private boolean shouldZero(OrderLineItem item) {
		ShippingData shipping = item.getShippingData();
		if (shipping == null || shipping.getShippingStatus() == null
			|| !shipping.getShippingStatus().isRefundTerminal()) {
			return false;
		}
		SettlementData settlement = item.getSettlementData();
		BigDecimal amount = settlement != null ? settlement.getSettlementAmount() : null;
		return amount != null && amount.compareTo(BigDecimal.ZERO) != 0;
	}
}

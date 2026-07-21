package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-098: 환불성 종결(취소·반품) lineItem의 정산액을 0으로 정규화하는 마켓 무관 서비스.
 *
 * <p>취소/반품은 대금이 고객에게 환불되므로 정산액이 0이어야 하나, 쿠팡(D-097 detectReturns)을 제외한
 * 마켓은 상태만 CANCELED/RETURNED로 반영하고 정산액을 부풀린 채 남겨 손익을 왜곡했다. 각 마켓 동기화
 * 사후처리에서 이 서비스를 호출해 DB의 종결 주문을 훑어 정산0으로 내린다. 상태 감지 경로(마켓별 API)와
 * 무관하게 DB에서 파생 가능하며, 이미 0인 건은 건드리지 않아 멱등이다.
 *
 * <p>교환(EXCHANGED)은 결제가 유지되므로 대상이 아니다({@link ShippingStatus#isRefundTerminal()}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminalSettlementService {

	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	/**
	 * 해당 마켓의 취소·반품 종결 lineItem 정산액을 0+verified로 정규화한다. 멱등.
	 *
	 * @return 0으로 전환한 lineItem 수
	 */
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
		// 멱등: 이미 0(또는 미설정)이면 스킵.
		return amount != null && amount.compareTo(BigDecimal.ZERO) != 0;
	}
}

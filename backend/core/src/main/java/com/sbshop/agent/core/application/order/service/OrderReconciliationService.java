package com.sbshop.agent.core.application.order.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.application.order.probe.MarketOrderProbeRouter;
import com.sbshop.agent.core.application.order.probe.OrderProbeResult;
import com.sbshop.agent.core.application.order.probe.OrderProbeStatus;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderReconciliationService {
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketOrderProbeRouter probeRouter;
	private final long probeDelayMillis;

	public OrderReconciliationService(OrderRepository orderRepository,
			OrderLineItemRepository orderLineItemRepository,
			MarketOrderProbeRouter probeRouter,
			@Value("${sbshop.order.probe-delay-ms:300}") long probeDelayMillis) {
		this.orderRepository = orderRepository;
		this.orderLineItemRepository = orderLineItemRepository;
		this.probeRouter = probeRouter;
		this.probeDelayMillis = probeDelayMillis;
	}

	@Transactional
	public int reconcile(MarketType marketType, LocalDate from, LocalDate to, Set<String> seenMarketOrderNos) {
		if (!probeRouter.has(marketType)) {
			return 0;
		}
		int changed = 0;
		int probed = 0;
		int missed = 0;
		for (Order order : orderRepository.findByMarketType(marketType)) {
			String orderNo = order.getMarketOrderNo();
			if (orderNo == null || seenMarketOrderNos.contains(orderNo)) {
				continue;
			}
			if (!withinWindow(order.getOrderDate(), from, to)) {
				continue;
			}
			probed++;
			OrderProbeResult result = probeRouter.probe(marketType, order);
			if (probeDelayMillis > 0) {
				try {
					Thread.sleep(probeDelayMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			ShippingStatus resolved = resolvedStatus(result);
			if (resolved == null) {
				if (result.status() == OrderProbeStatus.NOT_FOUND) {
					missed++;
				}
				log.info("[{}] 확증 미반영: orderNo={}, status={}, msg={}",
					marketType, orderNo, result.status(), result.rawMessage());
				continue;
			}
			changed += apply(order, resolved);
		}
		if (probed > 0) {
			log.info("[{}] 확증 완료: 프로브 {}건, 반영 {}건, 없음 {}건", marketType, probed, changed, missed);
		}
		return changed;
	}

	private boolean withinWindow(LocalDateTime orderDate, LocalDate from, LocalDate to) {
		if (orderDate == null) {
			return false;
		}
		LocalDate date = orderDate.toLocalDate();
		return !date.isBefore(from) && !date.isAfter(to);
	}

	private ShippingStatus resolvedStatus(OrderProbeResult result) {
		if (result.status() != OrderProbeStatus.FOUND && result.status() != OrderProbeStatus.TERMINATED) {
			return null;
		}
		ShippingStatus status = result.shippingStatus();
		return status == ShippingStatus.UNKNOWN ? null : status;
	}

	private int apply(Order order, ShippingStatus resolved) {
		int changed = 0;
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		for (OrderLineItem item : items) {
			ShippingStatus current = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			if (current == resolved) {
				continue;
			}
			ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
				.shippingStatus(resolved)
				.build();
			item.applyShippingData(cmd.toShippingData(item.getShippingData()));
			orderLineItemRepository.save(item);
			changed++;
			log.info("[{}] 확증 반영: orderNo={}, {} → {}",
				order.getMarketType(), order.getMarketOrderNo(), current, resolved);
		}
		return changed;
	}
}

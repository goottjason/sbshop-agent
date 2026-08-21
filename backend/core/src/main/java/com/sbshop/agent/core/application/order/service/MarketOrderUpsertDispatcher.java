package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class MarketOrderUpsertDispatcher {
	private MarketOrderUpsertDispatcher() {}

	static void dispatch(
		List<MarketOrderDto> orders,
		OrderRepository orderRepository,
		String logTag,
		BiConsumer<Order, MarketOrderDto> onExisting,
		Consumer<MarketOrderDto> onNew) {
		dispatch(orders, orderRepository, logTag, onExisting, onNew, true);
	}

	static void dispatch(
		List<MarketOrderDto> orders,
		OrderRepository orderRepository,
		String logTag,
		BiConsumer<Order, MarketOrderDto> onExisting,
		Consumer<MarketOrderDto> onNew,
		boolean createMissing) {
		for (MarketOrderDto dto : orders) {
			log.info("[{}] 처리 중: orderNo={}, status={}", logTag, dto.getMarketOrderNo(), dto.getStatus());
			Optional<Order> existingOrder = orderRepository.findByMarketOrderNo(dto.getMarketOrderNo());

			if (existingOrder.isPresent()) {
				log.info("[{}] 기존 주문 발견: id={}, orderNo={}",
					logTag, existingOrder.get().getId(), dto.getMarketOrderNo());
				onExisting.accept(existingOrder.get(), dto);
			} else if (createMissing) {
				log.info("[{}] 신규 주문 생성 시도: orderNo={}", logTag, dto.getMarketOrderNo());
				onNew.accept(dto);
			} else {
				log.debug("[{}] 갱신 전용 모드 — 없는 주문은 만들지 않는다: orderNo={}",
					logTag, dto.getMarketOrderNo());
			}
		}
	}
}

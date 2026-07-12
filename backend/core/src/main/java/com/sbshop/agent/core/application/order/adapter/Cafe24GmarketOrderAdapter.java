package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.service.Cafe24ShipmentService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * G마켓(GMARKET) 주문 어댑터 — Cafe24 주문 API 기반(ESM+ Selenium 대체).
 * 조회는 Cafe24OrderSyncService가 담당하므로 fetchOrders는 미사용(빈 리스트).
 * 발주확인/취소는 Cafe24 주문상태 API, 송장은 Cafe24 shipments API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24GmarketOrderAdapter implements MarketOrderPort {

	private final Cafe24OrderApiPort cafe24OrderApiPort;
	private final Cafe24ShipmentService cafe24ShipmentService;

	@Override
	public MarketType getMarketType() {
		return MarketType.GMARKET;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential, LocalDate fromDate, LocalDate toDate) {
		// G마켓 조회는 Cafe24OrderSyncService(order_place_id=gmarket)가 담당 — 여기선 미사용.
		return List.of();
	}

	@Override
	public void shipOrder(MarketCredential credential, Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		cafe24ShipmentService.ship(order, trackingNo, carrier);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		cafe24OrderApiPort.acceptOrder(order.getCafe24OrderId());
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		cafe24OrderApiPort.cancelOrder(order.getCafe24OrderId());
	}
}

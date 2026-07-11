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
 * 옥션(AUCTION) 배송 포트. 주문 조회는 Cafe24OrderSyncService가 담당하고,
 * 이 어댑터는 배송(송장 역전송)을 Cafe24 주문 API로 처리한다(GMARKET은 Cafe24GmarketOrderAdapter가 담당).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24AuctionOrderAdapter implements MarketOrderPort {

	private final Cafe24ShipmentService cafe24ShipmentService;
	private final Cafe24OrderApiPort cafe24OrderApiPort;

	@Override
	public MarketType getMarketType() {
		return MarketType.AUCTION;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential, LocalDate fromDate, LocalDate toDate) {
		// 옥션 주문 조회는 Cafe24OrderSyncService(order_place_id=auction)가 담당 — 여기선 미사용.
		return List.of();
	}

	@Override
	public void shipOrder(MarketCredential credential, Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		cafe24ShipmentService.ship(order, trackingNo, carrier);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		cafe24OrderApiPort.acceptOrder(order.getMarketOrderNo());
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		cafe24OrderApiPort.cancelOrder(order.getMarketOrderNo());
	}
}

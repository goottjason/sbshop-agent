package com.sbshop.agent.core.application.order.port;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public interface MarketOrderPort {
	MarketType getMarketType();

	List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate);

	void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier);

	default void updateTracking(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		shipOrder(credential, order, lineItem, trackingNo, carrier);
	}

	void acceptOrders(MarketCredential credential, Order order);

	default void cancelOrder(MarketCredential credential, Order order) {
		throw new UnsupportedOperationException("이 마켓은 주문 취소를 지원하지 않습니다.");
	}

	default Map<String, BigDecimal> querySettlement(MarketCredential credential,
		LocalDate from, LocalDate to) {
		return Collections.emptyMap();
	}

	default MarketOrderDto fetchOrderDetail(MarketCredential credential, MarketOrderDto dto) {
		return null;
	}
}

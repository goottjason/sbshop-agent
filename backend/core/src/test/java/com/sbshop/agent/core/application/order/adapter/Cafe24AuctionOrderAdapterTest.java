package com.sbshop.agent.core.application.order.adapter;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24ShipmentService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24AuctionOrderAdapterTest {
	@Mock
	Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock
	Cafe24ShipmentService cafe24ShipmentService;
	@Mock
	MarketCredential credential;
	@Mock
	Order order;

	@Test
	@DisplayName("acceptOrders는 order.getCafe24OrderId()(cafe24_order_id)로 Cafe24를 타깃한다(마켓번호 아님)")
	void acceptUsesCafe24OrderId() {
		when(order.getCafe24OrderId()).thenReturn("20260708-0000011");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.acceptOrders(credential, order);
		verify(cafe24OrderApiPort).acceptOrder("20260708-0000011");
	}

	@Test
	@DisplayName("cancelOrder는 order.getCafe24OrderId()(cafe24_order_id)로 Cafe24를 타깃한다")
	void cancelUsesCafe24OrderId() {
		when(order.getCafe24OrderId()).thenReturn("20260708-0000011");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.cancelOrder(credential, order);
		verify(cafe24OrderApiPort).cancelOrder("20260708-0000011");
	}

	@Test
	@DisplayName("폴백은 getCafe24OrderId()가 담당 — 어댑터는 반환값(marketOrderNo)을 그대로 타깃한다")
	void fallsBackToMarketOrderNoWhenNoCafe24OrderId() {
		when(order.getCafe24OrderId()).thenReturn("4466411168");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.cancelOrder(credential, order);
		verify(cafe24OrderApiPort).cancelOrder("4466411168");
	}
}

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

	@Mock Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock Cafe24ShipmentService cafe24ShipmentService;
	@Mock MarketCredential credential;
	@Mock Order order;

	@Test
	@DisplayName("acceptOrders는 Cafe24 acceptOrder(marketOrderNo) 호출")
	void accept() {
		when(order.getMarketOrderNo()).thenReturn("A55");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.acceptOrders(credential, order);
		verify(cafe24OrderApiPort).acceptOrder("A55");
	}

	@Test
	@DisplayName("cancelOrder는 Cafe24 cancelOrder(marketOrderNo) 호출")
	void cancel() {
		when(order.getMarketOrderNo()).thenReturn("A55");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.cancelOrder(credential, order);
		verify(cafe24OrderApiPort).cancelOrder("A55");
	}
}

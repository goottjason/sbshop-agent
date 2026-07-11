package com.sbshop.agent.core.application.order.adapter;

import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24ShipmentService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24GmarketOrderAdapterTest {

	@Mock Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock Cafe24ShipmentService cafe24ShipmentService;
	@Mock MarketCredential credential;
	@Mock Order order;

	@Test
	@DisplayName("marketType은 GMARKET")
	void marketType() {
		var adapter = new Cafe24GmarketOrderAdapter(cafe24OrderApiPort, cafe24ShipmentService);
		assertThat(adapter.getMarketType()).isEqualTo(MarketType.GMARKET);
	}

	@Test
	@DisplayName("acceptOrders는 Cafe24 acceptOrder(marketOrderNo) 호출")
	void accept() {
		org.mockito.Mockito.when(order.getMarketOrderNo()).thenReturn("O777");
		var adapter = new Cafe24GmarketOrderAdapter(cafe24OrderApiPort, cafe24ShipmentService);
		adapter.acceptOrders(credential, order);
		verify(cafe24OrderApiPort).acceptOrder("O777");
	}

	@Test
	@DisplayName("cancelOrder는 Cafe24 cancelOrder(marketOrderNo) 호출")
	void cancel() {
		org.mockito.Mockito.when(order.getMarketOrderNo()).thenReturn("O777");
		var adapter = new Cafe24GmarketOrderAdapter(cafe24OrderApiPort, cafe24ShipmentService);
		adapter.cancelOrder(credential, order);
		verify(cafe24OrderApiPort).cancelOrder("O777");
	}
}

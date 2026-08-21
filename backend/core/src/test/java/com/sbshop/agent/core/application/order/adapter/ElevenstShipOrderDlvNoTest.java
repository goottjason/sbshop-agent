package com.sbshop.agent.core.application.order.adapter;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstShipOrderDlvNoTest {
	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	@Mock
	private ElevenstStatusMapper statusMapper;

	@Test
	@DisplayName("[D-127] 발송처리는 주문번호가 아니라 marketSpecificData의 배송번호(dlvNo)를 전달한다")
	void shipOrder_sendsDeliveryNoNotOrderNo() {
		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		Order order = orderWith(Map.of(
			"ordPrdSeq", "1", "addPrdYn", "N", "addPrdNo", "0", "dlvNo", "2716448228"));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		adapter.shipOrder(credential, order, null, "424079080471", ShippingCarrier.CJ_LOGISTICS);

		verify(elevenstOrderApiPort).shipOrder(
			eq("api-key"), anyString(), eq("01"), eq("00034"), eq("424079080471"), eq("2716448228"));
	}

	@Test
	@DisplayName("[D-127] 배송번호를 모르면 주문번호로 대체하지 않고 명확히 실패한다")
	void shipOrder_failsFastWhenDeliveryNoMissing() {
		MarketCredential credential = mock(MarketCredential.class);
		Order order = orderWith(Map.of("ordPrdSeq", "1"));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);

		assertThatThrownBy(
			() -> adapter.shipOrder(credential, order, null, "424079080471", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("배송번호");
	}

	private Order orderWith(Map<String, String> marketData) {
		Order order = Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260731088778989")
			.orderDate(LocalDateTime.now())
			.build();
		if (marketData != null) {
			order.setMarketSpecificDataFromMap(marketData);
		}
		return order;
	}
}

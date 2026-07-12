package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderTest {

	private Order order(String marketOrderNo) {
		return Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo(marketOrderNo)
			.orderDate(LocalDateTime.now())
			.build();
	}

	@Test
	@DisplayName("getCafe24OrderId는 marketSpecific의 cafe24_order_id를 우선한다(마켓 원본번호 아님)")
	void prefersCafe24OrderId() {
		Order order = order("4466411168");
		order.setMarketSpecificDataFromMap(Map.of("cafe24_order_id", "20260708-0000011"));
		assertThat(order.getCafe24OrderId()).isEqualTo("20260708-0000011");
	}

	@Test
	@DisplayName("getCafe24OrderId는 cafe24_order_id가 없으면 marketOrderNo로 폴백한다")
	void fallsBackToMarketOrderNo() {
		assertThat(order("4466411168").getCafe24OrderId()).isEqualTo("4466411168");
	}
}

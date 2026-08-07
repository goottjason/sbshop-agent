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

	private Order orderWithPhones(String recipientPhone, String ordererPhone) {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("C1")
			.orderDate(LocalDateTime.now())
			.recipientPhone(recipientPhone)
			.ordererPhone(ordererPhone)
			.build();
	}

	@Test
	@DisplayName("update: 마스킹(*** 포함) 전화번호로는 기존 실번호를 덮지 않는다(쿠팡 배송완료 마스킹 방어)")
	void keepsRealPhoneAgainstMask() {
		Order o = orderWithPhones("01011112222", "01055556666");
		o.update("수취인", "***-****-****", null, null, null, "주문자", "***-****-****", MarketType.COUPANG);
		assertThat(o.getRecipientPhone()).isEqualTo("01011112222");
		assertThat(o.getOrdererPhone()).isEqualTo("01055556666");
	}

	@Test
	@DisplayName("update: 빈/공백 전화번호로는 기존 실번호를 덮지 않는다(안심번호 만료 방어)")
	void keepsRealPhoneAgainstBlank() {
		Order o = orderWithPhones("01011112222", "01055556666");
		o.update("수취인", "", null, null, null, "주문자", "   ", MarketType.COUPANG);
		assertThat(o.getRecipientPhone()).isEqualTo("01011112222");
		assertThat(o.getOrdererPhone()).isEqualTo("01055556666");
	}

	@Test
	@DisplayName("update: 고객이 실번호를 변경하면 새 실번호로 업데이트된다(정상 변경 허용)")
	void allowsRealPhoneChange() {
		Order o = orderWithPhones("01011112222", "01055556666");
		o.update("수취인", "01033334444", null, null, null, "주문자", "01077778888", MarketType.COUPANG);
		assertThat(o.getRecipientPhone()).isEqualTo("01033334444");
		assertThat(o.getOrdererPhone()).isEqualTo("01077778888");
	}

	@Test
	@DisplayName("update: 비어있던 전화번호에 실번호가 처음 오면 채운다")
	void fillsBlankPhone() {
		Order o = orderWithPhones(null, null);
		o.update("수취인", "01033334444", null, null, null, "주문자", null, MarketType.COUPANG);
		assertThat(o.getRecipientPhone()).isEqualTo("01033334444");
		assertThat(o.getOrdererPhone()).isNull(); // null 유입은 스킵(기존값 유지)
	}
}

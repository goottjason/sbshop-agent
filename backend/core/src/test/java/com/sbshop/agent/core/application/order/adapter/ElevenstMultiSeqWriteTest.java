package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

@ExtendWith(MockitoExtension.class)
class ElevenstMultiSeqWriteTest {
	private static final String ORD_NO = "20260731088778989";

	@Mock
	private ElevenstOrderApiPort api;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("다품목 주문은 모든 상품주문을 발주확인한다 — 하나만 하면 주문이 결제완료에 남는다")
	void confirmsEveryProductOrder() {
		adapter().acceptOrders(credential(),
			order(data("ordPrdSeqs", "1|2", "ordPrdSeq", "1", "dlvNo", "2716448228")));

		verify(api).confirmOrder("api-key", ORD_NO, "1", "N", "0", "2716448228");
		verify(api).confirmOrder("api-key", ORD_NO, "2", "N", "0", "2716448228");
	}

	@Test
	@DisplayName("단일 상품 주문은 종전과 같이 한 번만 호출한다")
	void confirmsOnceForSingleProductOrder() {
		adapter().acceptOrders(credential(),
			order(data("ordPrdSeqs", "1", "ordPrdSeq", "1", "dlvNo", "2716448228")));

		verify(api).confirmOrder("api-key", ORD_NO, "1", "N", "0", "2716448228");
		verify(api, never()).confirmOrder(anyString(), anyString(), eq("2"), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("ordPrdSeqs가 없는 레거시 주문은 ordPrdSeq 하나로 폴백한다")
	void fallsBackToSingleSeqForLegacyOrders() {
		adapter().acceptOrders(credential(),
			order(data("ordPrdSeq", "1", "dlvNo", "2716448228")));

		verify(api).confirmOrder("api-key", ORD_NO, "1", "N", "0", "2716448228");
	}

	@Test
	@DisplayName("발주확인 정보가 아예 없으면 즉시 실패한다")
	void failsWhenNoSeqAtAll() {
		assertThatThrownBy(() -> adapter().acceptOrders(
			mock(MarketCredential.class), order(data("dlvNo", "D1"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("발주확인");
	}

	@Test
	@DisplayName("상품주문 식별자를 알면 부분발송으로 그 상품주문만 보낸다 — 묶음 전체가 나가지 않는다")
	void usesPartialDispatchWhenSeqKnown() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(1L).quantity(1).marketLineItemNo("2").build();

		adapter().shipOrder(credential(), order(data("dlvNo", "2716448228")), item,
			"424079080471", ShippingCarrier.CJ_LOGISTICS);

		verify(api).shipOrderPartial(eq("api-key"), anyString(), eq("01"), eq("00034"),
			eq("424079080471"), eq("2716448228"), eq(ORD_NO), eq("2"));
		verify(api, never()).shipOrder(anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString());
	}

	@Test
	@DisplayName("상품주문 식별자를 모르면 종전대로 전체 발송처리를 쓴다")
	void fallsBackToFullDispatchWhenSeqUnknown() {
		OrderLineItem item = OrderLineItem.builder().orderId(1L).quantity(1).build();

		adapter().shipOrder(credential(), order(data("dlvNo", "2716448228")), item,
			"424079080471", ShippingCarrier.CJ_LOGISTICS);

		verify(api).shipOrder(eq("api-key"), anyString(), eq("01"), eq("00034"),
			eq("424079080471"), eq("2716448228"));
		verify(api, never()).shipOrderPartial(anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString());
	}

	@Test
	@DisplayName("배송번호가 없으면 발송처리하지 않고 즉시 실패한다 (D-127)")
	void failsWithoutDeliveryNo() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(1L).quantity(1).marketLineItemNo("1").build();

		assertThatThrownBy(() -> adapter().shipOrder(credential(), order(data()), item,
			"424079080471", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("배송번호");
	}

	private ElevenstOrderAdapter adapter() {
		return new ElevenstOrderAdapter(api, statusMapper);
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("api-key");
		return c;
	}

	private Order order(Map<String, String> marketData) {
		Order o = Order.builder().marketOrderNo(ORD_NO).build();
		o.setMarketSpecificDataFromMap(marketData);
		return o;
	}

	private static Map<String, String> data(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}
}

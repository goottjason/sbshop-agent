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

/**
 * 2단계 쓰기 경로: 11번가는 <b>발주확인이 상품주문 단위</b>, <b>발송처리가 배송 단위</b>다(설계 6).
 *
 * <p>발주확인이 마켓에 반영되지 않는다는 오래된 미해결 항목의 유력한 원인이 여기 있다 —
 * 다품목 주문에서 {@code ordPrdSeq=1}만 발주확인하면 순번 2가 남아 주문이 결제완료 목록에
 * 계속 머문다. 그래서 실제로 "API는 성공(result_code=0)인데 배송준비중 목록은 0건"이었다.
 *
 * <p>발송처리는 반대 방향의 함정이 있다. 전체 발송처리는 <b>묶음배송번호가 같은 주문번호를
 * 모두</b> 발송 처리한다(에러코드 -3308 설명). 한 상품주문만 보내려는데 묶음 전체가 나가면
 * 아직 준비되지 않은 상품이 발송된 것으로 마켓에 기록된다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstMultiSeqWriteTest {

	private static final String ORD_NO = "20260731088778989";

	@Mock
	private ElevenstOrderApiPort api;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

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

	// ======================== 발주확인 ========================

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
		// 이 필드는 2단계에서 새로 채우기 시작한다. 이미 저장된 주문에는 없다.
		adapter().acceptOrders(credential(),
			order(data("ordPrdSeq", "1", "dlvNo", "2716448228")));

		verify(api).confirmOrder("api-key", ORD_NO, "1", "N", "0", "2716448228");
	}

	@Test
	@DisplayName("발주확인 정보가 아예 없으면 즉시 실패한다")
	void failsWhenNoSeqAtAll() {
		// credential은 읽히기 전에 실패한다 — 스텁을 두면 UnnecessaryStubbing이 된다.
		assertThatThrownBy(() -> adapter().acceptOrders(
			mock(MarketCredential.class), order(data("dlvNo", "D1"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("발주확인");
	}

	// ======================== 발송처리 ========================

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
		// 레거시 라인아이템(market_line_item_no NULL). 아직 채택되지 않은 240행이 이 형태다.
		OrderLineItem item = OrderLineItem.builder().orderId(1L).quantity(1).build();

		adapter().shipOrder(credential(), order(data("dlvNo", "2716448228")), item,
			"424079080471", ShippingCarrier.CJ_LOGISTICS);

		// sendDt는 호출 시각이라 값을 고정할 수 없다.
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
}

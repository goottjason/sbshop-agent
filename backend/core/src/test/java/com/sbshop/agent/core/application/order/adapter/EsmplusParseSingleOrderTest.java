package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.EsmplusStatusMapper;
import com.sbshop.agent.core.application.order.port.EsmplusOrderApiPort;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-032 회귀 방지: 미인식 deliveryStatusCode일 때 문자열 폴백이 실제 적용돼야 한다
 * (기존 `status==NEW && code!=1010` 조건은 논리 모순 DEAD CODE였음 → `status==UNKNOWN`으로 교정).
 * D-029 회귀 방지: 취소/교환 주문을 null로 필터링하지 않고 파싱 결과에 포함시켜야
 * 정상 처리 경로(processOrders→updateExistingOrder)에서 기존 DB 주문 상태가 갱신된다.
 */
@ExtendWith(MockitoExtension.class)
class EsmplusParseSingleOrderTest {

	@Mock
	private EsmplusOrderApiPort esmplusOrderApiPort;

	private EsmplusOrderAdapter adapter() {
		return new EsmplusOrderAdapter(esmplusOrderApiPort, new EsmplusStatusMapper(),
			org.mockito.Mockito.mock(
				com.sbshop.agent.core.application.order.service.Cafe24ShipmentService.class));
	}

	private String orderJson(String siteOrderNo, int deliveryStatusCode, String deliveryStatus) {
		return "{"
			+ "\"orderNo\":\"O-" + siteOrderNo + "\","
			+ "\"siteOrderNo\":\"" + siteOrderNo + "\","
			+ "\"siteId\":2,"
			+ "\"goodsNo\":\"G1\",\"goodsName\":\"테스트상품\","
			+ "\"buyerId\":\"b1\",\"rcverName\":\"수령인\",\"buyerName\":\"구매자\","
			+ "\"tradeAmnt\":10000,\"orderQty\":1,"
			+ "\"deliveryStatusCode\":" + deliveryStatusCode + ","
			+ "\"deliveryStatus\":\"" + deliveryStatus + "\","
			+ "\"depositConfirmDate\":\"2026-07-01T10:00:00\""
			+ "}";
	}

	private String body(String... orderJsons) {
		return "{\"resultCode\":0,\"data\":{\"list\":[" + String.join(",", orderJsons) + "]}}";
	}

	@Test
	@DisplayName("[D-032] 미인식 코드(9999)+문자열 '배송중'이면 문자열 폴백으로 SHIPPED가 된다")
	void unrecognizedCode_fallsBackToStringStatus() {
		List<MarketOrderDto> result = adapter().parseOrdersFromJson(body(orderJson("S1", 9999, "배송중")));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("[D-029] 취소(2010) 주문은 null로 필터링되지 않고 CANCELED 상태로 포함된다")
	void canceledOrder_isIncludedNotFiltered() {
		List<MarketOrderDto> result = adapter().parseOrdersFromJson(body(orderJson("C1", 2010, "주문취소")));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMarketOrderNo()).isEqualTo("C1");
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("[D-029] 교환(2050) 주문도 EXCHANGED 상태로 파싱 결과에 포함된다")
	void exchangedOrder_isIncludedNotFiltered() {
		List<MarketOrderDto> result = adapter().parseOrdersFromJson(body(orderJson("X1", 2050, "교환접수")));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("[D-029/D-032] 회귀 방지: 정상 배송중(1040) 주문은 SHIPPED로 그대로 포함된다")
	void normalShippedOrder_unchanged() {
		List<MarketOrderDto> result = adapter().parseOrdersFromJson(body(orderJson("N1", 1040, "배송중")));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}
}

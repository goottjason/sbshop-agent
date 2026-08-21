package com.sbshop.agent.core.application.order.adapter;

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

/**
 * D-127: 11번가 발송처리(reqdelivery)의 마지막 경로변수는 <b>배송번호(dlvNo)</b>인데
 * 어댑터가 <b>주문번호(ordNo)</b>를 넘기고 있어 라이브에서 항상
 * {@code "존재하지 않는 배송번호 입니다."}(code=-1)로 실패했다.
 *
 * <p>같은 클래스의 {@code acceptOrders}(발주확인)는 {@code marketSpecificData}의 dlvNo를 올바르게
 * 사용하고 있었다 — 한 어댑터 안에서 같은 값의 출처가 갈렸던 것이 결함의 형태다. 동기화가
 * marketSpecificData에 dlvNo를 저장해 두므로 발송처리도 같은 출처를 써야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstShipOrderDlvNoTest {

	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	@Mock
	private ElevenstStatusMapper statusMapper;

	private Order orderWith(Map<String, String> marketData) {
		Order order = Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260731088778989")
			.orderDate(java.time.LocalDateTime.now())
			.build();
		if (marketData != null) {
			order.setMarketSpecificDataFromMap(marketData);
		}
		return order;
	}

	@Test
	@DisplayName("[D-127] 발송처리는 주문번호가 아니라 marketSpecificData의 배송번호(dlvNo)를 전달한다")
	void shipOrder_sendsDeliveryNoNotOrderNo() {
		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		Order order = orderWith(Map.of(
			"ordPrdSeq", "1", "addPrdYn", "N", "addPrdNo", "0", "dlvNo", "2716448228"));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		adapter.shipOrder(credential, order, null, "424079080471", ShippingCarrier.CJ_LOGISTICS);

		// 마지막 인자가 배송번호여야 한다. 과거엔 주문번호(20260731088778989)가 넘어가 항상 실패했다.
		verify(elevenstOrderApiPort).shipOrder(
			eq("api-key"), anyString(), eq("01"), eq("00034"), eq("424079080471"), eq("2716448228"));
	}

	@Test
	@DisplayName("[D-127] 배송번호를 모르면 주문번호로 대체하지 않고 명확히 실패한다")
	void shipOrder_failsFastWhenDeliveryNoMissing() {
		MarketCredential credential = mock(MarketCredential.class);
		Order order = orderWith(Map.of("ordPrdSeq", "1"));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);

		// 주문번호 폴백은 "존재하지 않는 배송번호" 실패를 낳을 뿐이므로, 조용한 실패보다 즉시 오류가 낫다.
		assertThatThrownBy(
			() -> adapter.shipOrder(credential, order, null, "424079080471", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("배송번호");
	}
}

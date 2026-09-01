package com.sbshop.agent.core.application.order.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class Cafe24OrderProbeTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private Cafe24OrderApiPort port;
	private Cafe24OrderProbe probe;
	private Order order;

	@BeforeEach
	void setUp() {
		port = mock(Cafe24OrderApiPort.class);
		probe = new Cafe24OrderProbe(port);
		order = mock(Order.class);
		when(order.getCafe24OrderId()).thenReturn("20260630-0000017");
	}

	private void respond(String json) throws Exception {
		when(port.fetchOrderDetail(eq("20260630-0000017"))).thenReturn(mapper.readTree(json));
	}

	@Test
	@DisplayName("배송완료(T)는 FOUND + DELIVERED 다")
	void delivered() throws Exception {
		respond("{\"order_id\":\"20260630-0000017\",\"shipping_status\":\"T\",\"canceled\":\"F\"}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.FOUND);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("배송중(M)은 FOUND + SHIPPED 다")
	void shipped() throws Exception {
		respond("{\"order_id\":\"20260630-0000017\",\"shipping_status\":\"M\",\"canceled\":\"F\"}");

		assertThat(probe.probe(order).shippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("반품확정일이 있으면 TERMINATED + RETURNED 로 확정한다")
	void returned() throws Exception {
		respond("{\"order_id\":\"20260630-0000017\",\"shipping_status\":\"M\",\"canceled\":\"T\","
			+ "\"return_confirmed_date\":\"2026-07-27T12:17:00+09:00\"}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.TERMINATED);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("반품확정일 없이 취소면 TERMINATED + CANCELED 다")
	void canceled() throws Exception {
		respond("{\"order_id\":\"20260630-0000017\",\"shipping_status\":\"F\",\"canceled\":\"T\"}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.TERMINATED);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("order 가 비면 NOT_FOUND 다")
	void notFound() throws Exception {
		when(order.getCafe24OrderId()).thenReturn("없는번호");
		when(port.fetchOrderDetail(eq("없는번호"))).thenReturn(mapper.readTree("null"));

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("카페24 주문 아이디가 없으면 UNKNOWN 이다 — 물어볼 키가 없다")
	void noCafe24OrderId() {
		when(order.getCafe24OrderId()).thenReturn(null);

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.UNKNOWN);
	}

	@Test
	@DisplayName("모르는 배송상태 코드는 UNKNOWN 이다")
	void unknownCode() throws Exception {
		respond("{\"order_id\":\"20260630-0000017\",\"shipping_status\":\"Z\",\"canceled\":\"F\"}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.UNKNOWN);
		assertThat(result.shippingStatus()).isNull();
	}

	@Test
	@DisplayName("조회가 예외를 던지면 UNKNOWN 이다 — 못 읽은 것을 사라진 것으로 읽지 않는다")
	void exceptionIsUnknown() {
		when(port.fetchOrderDetail(eq("20260630-0000017"))).thenThrow(new RuntimeException("timeout"));

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.UNKNOWN);
	}
}

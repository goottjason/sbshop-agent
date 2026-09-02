package com.sbshop.agent.core.application.order.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.OrderProbeStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class CoupangOrderProbeTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private CoupangOrderApiPort port;
	private MarketCredentialRepository credentialRepository;
	private CoupangOrderProbe probe;

	@BeforeEach
	void setUp() {
		port = mock(CoupangOrderApiPort.class);
		credentialRepository = mock(MarketCredentialRepository.class);
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(mock(MarketCredential.class)));
		probe = new CoupangOrderProbe(port, credentialRepository, new CoupangStatusMapper());
	}

	private final Order order = mock(Order.class);

	private void respond(String json) throws Exception {
		when(order.getMarketOrderNo()).thenReturn("ORD-1");
		when(port.fetchOrderById(any(), eq("ORD-1"))).thenReturn(mapper.readTree(json));
	}

	@Test
	@DisplayName("정상 주문은 FOUND 와 매핑된 배송상태를 돌려준다")
	void found() throws Exception {
		respond("{\"data\":[{\"status\":\"FINAL_DELIVERY\"}]}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.FOUND);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("취소 또는 반품 문구는 TERMINATED 로 읽되 어느 쪽인지 단정하지 않는다")
	void terminated() throws Exception {
		respond("{\"code\":400,\"message\":\"해당 주문이 취소 또는 반품 되었습니다.\"}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.TERMINATED);
		assertThat(result.shippingStatus()).isNull();
	}

	@Test
	@DisplayName("유효하지 않은 주문번호 문구는 NOT_FOUND 다")
	void notFound() throws Exception {
		respond("{\"code\":400,\"message\":\"유효하지 않은 주문번호 입니다.\"}");

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("모르는 문구는 UNKNOWN 이다 — 마켓이 문구를 바꾸면 상태를 건드리지 않는다")
	void unknownMessage() throws Exception {
		respond("{\"code\":500,\"message\":\"일시적인 오류가 발생했습니다.\"}");

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.UNKNOWN);
		assertThat(result.shippingStatus()).isNull();
	}

	@Test
	@DisplayName("크레덴셜이 없으면 UNKNOWN 이다")
	void noCredential() {
		when(order.getMarketOrderNo()).thenReturn("ORD-1");
		when(credentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.empty());

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.UNKNOWN);
	}
}

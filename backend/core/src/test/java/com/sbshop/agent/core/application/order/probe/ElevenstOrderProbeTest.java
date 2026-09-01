package com.sbshop.agent.core.application.order.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class ElevenstOrderProbeTest {

	private ElevenstOrderApiPort port;
	private MarketCredentialRepository credentialRepository;
	private ElevenstOrderProbe probe;
	private Order order;

	@BeforeEach
	void setUp() {
		port = mock(ElevenstOrderApiPort.class);
		credentialRepository = mock(MarketCredentialRepository.class);
		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("KEY");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(Optional.of(credential));
		probe = new ElevenstOrderProbe(port, credentialRepository, new ElevenstStatusMapper());
		order = mock(Order.class);
		when(order.getMarketOrderNo()).thenReturn("ORD-1");
	}

	private Element row(String statusName) throws Exception {
		String xml = "<row><ordPrdStatNm>" + statusName + "</ordPrdStatNm></row>";
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	@Test
	@DisplayName("배송중은 FOUND + SHIPPED 다")
	void shipped() throws Exception {
		when(port.fetchProductOrderStatuses(any(), eq("ORD-1"))).thenReturn(List.of(row("배송중")));

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.FOUND);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("취소완료는 TERMINATED + CANCELED 다")
	void canceled() throws Exception {
		when(port.fetchProductOrderStatuses(any(), eq("ORD-1"))).thenReturn(List.of(row("취소완료")));

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.TERMINATED);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("여러 상품주문 중 하나라도 종결이면 종결로 본다")
	void anyTerminatedWins() throws Exception {
		when(port.fetchProductOrderStatuses(any(), eq("ORD-1")))
			.thenReturn(List.of(row("배송중"), row("반품완료")));

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.TERMINATED);
		assertThat(result.shippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("빈 응답은 NOT_FOUND 다")
	void notFound() {
		when(port.fetchProductOrderStatuses(any(), eq("ORD-1"))).thenReturn(List.of());

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("매핑되지 않는 상태명은 UNKNOWN 이다")
	void unknownName() throws Exception {
		when(port.fetchProductOrderStatuses(any(), eq("ORD-1"))).thenReturn(List.of(row("듣도보도못한상태")));

		OrderProbeResult result = probe.probe(order);

		assertThat(result.status()).isEqualTo(OrderProbeStatus.UNKNOWN);
		assertThat(result.shippingStatus()).isNull();
	}

	@Test
	@DisplayName("조회가 예외를 던지면 UNKNOWN 이다")
	void exceptionIsUnknown() {
		when(port.fetchProductOrderStatuses(any(), eq("ORD-1"))).thenThrow(new RuntimeException("timeout"));

		assertThat(probe.probe(order).status()).isEqualTo(OrderProbeStatus.UNKNOWN);
	}
}

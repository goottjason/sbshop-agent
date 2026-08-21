package com.sbshop.agent.core.application.order.adapter;

import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Element;

@ExtendWith(MockitoExtension.class)
class ElevenstShippingCustomsClearanceTest {
	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	@Mock
	private ElevenstStatusMapper statusMapper;

	@Test
	@DisplayName("[D-066] 배송중 파서는 psnCscUniqNo를 customsClearanceNo로 매핑한다")
	void shippingOrder_mapsPersonalCustomsClearanceNo() throws Exception {
		MarketCredential credential = credential();
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(shippingElement("20260701000001", "P123456789012")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));

		MarketOrderDto shipped = result.stream()
			.filter(dto -> "20260701000001".equals(dto.getMarketOrderNo()))
			.findFirst()
			.orElseThrow();
		assertThat(shipped.getCustomsClearanceNo()).isEqualTo("P123456789012");
	}

	@Test
	@DisplayName("[D-066] 통관번호 태그가 없는 배송중 주문은 null이며 예외 없이 통과한다")
	void shippingOrder_withoutCustomsTag_isNull() throws Exception {
		MarketCredential credential = credential();
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(shippingElement("20260701000002", null)));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));

		MarketOrderDto shipped = result.stream()
			.filter(dto -> "20260701000002".equals(dto.getMarketOrderNo()))
			.findFirst()
			.orElseThrow();
		assertThat(shipped.getCustomsClearanceNo()).isNull();
	}

	private Element shippingElement(String ordNo, String psnCscUniqNo) throws Exception {
		StringBuilder xml = new StringBuilder("<order><ordNo>").append(ordNo).append("</ordNo>");
		if (psnCscUniqNo != null) {
			xml.append("<psnCscUniqNo>").append(psnCscUniqNo).append("</psnCscUniqNo>");
		}
		xml.append("</order>");
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	private MarketCredential credential() {
		MarketCredential credential = Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		return credential;
	}
}

package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
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
class ElevenstShippingRecipientPreservationTest {
	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("[D-107] 배송중 파서는 rcvrNm 태그가 없으면 recipientName을 null로 둔다(기존 이름 보존)")
	void shippingOrder_withoutRecipientTag_nameIsNull() throws Exception {
		MarketOrderDto shipped = fetchShipping(shippingElement("20260701000010", null, null), "20260701000010");
		assertThat(shipped.getRecipientName()).isNull();
		assertThat(shipped.getAddress()).isNull();
	}

	@Test
	@DisplayName("[D-107] 배송중 파서는 rcvrNm/주소 태그가 있으면 파싱해 복원한다")
	void shippingOrder_withRecipientTag_isParsed() throws Exception {
		MarketOrderDto shipped = fetchShipping(
			shippingElement("20260701000011", "홍길동", "서울시 강남구"), "20260701000011");
		assertThat(shipped.getRecipientName()).isEqualTo("홍길동");
		assertThat(shipped.getAddress()).contains("서울시 강남구");
	}

	@Test
	@DisplayName("[D-107] 배송중 목록이 이름을 안 주면 단건 상세조회(rcvrNm)로 복원한다")
	void shippingOrder_withoutName_enrichesFromDetail() throws Exception {
		String ordNo = "20260701000012";
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(shippingElement(ordNo, null, null)));
		when(elevenstOrderApiPort.fetchOrderDetail(anyString(), anyString()))
			.thenReturn(List.of(detailElement(ordNo, "김수취", "부산시 해운대구 100")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		MarketOrderDto shipped = adapter.fetchOrders(credential(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1))
			.stream().filter(dto -> ordNo.equals(dto.getMarketOrderNo())).findFirst().orElseThrow();

		assertThat(shipped.getRecipientName()).isEqualTo("김수취");
		assertThat(shipped.getAddress()).contains("부산시 해운대구");
		assertThat(shipped.getShipments().get(0).getLineItems().get(0).getStatus())
			.isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("[D-107] 상세조회도 이름을 안 주면 null 유지(기존 값 보존)")
	void shippingOrder_detailAlsoBlank_staysNull() throws Exception {
		String ordNo = "20260701000013";
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(shippingElement(ordNo, null, null)));
		when(elevenstOrderApiPort.fetchOrderDetail(anyString(), anyString()))
			.thenReturn(List.of(detailElement(ordNo, null, null)));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		MarketOrderDto shipped = adapter.fetchOrders(credential(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1))
			.stream().filter(dto -> ordNo.equals(dto.getMarketOrderNo())).findFirst().orElseThrow();

		assertThat(shipped.getRecipientName()).isNull();
	}

	private Element shippingElement(String ordNo, String rcvrNm, String rcvrBaseAddr) throws Exception {
		StringBuilder xml = new StringBuilder("<order><ordNo>").append(ordNo).append("</ordNo>");
		if (rcvrNm != null) {
			xml.append("<rcvrNm>").append(rcvrNm).append("</rcvrNm>");
		}
		if (rcvrBaseAddr != null) {
			xml.append("<rcvrBaseAddr>").append(rcvrBaseAddr).append("</rcvrBaseAddr>");
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

	private MarketOrderDto fetchShipping(Element element, String ordNo) {
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(element));
		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		return adapter.fetchOrders(credential(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1))
			.stream()
			.filter(dto -> ordNo.equals(dto.getMarketOrderNo()))
			.findFirst()
			.orElseThrow();
	}

	private Element detailElement(String ordNo, String rcvrNm, String rcvrBaseAddr) throws Exception {
		StringBuilder xml = new StringBuilder("<order><ordNo>").append(ordNo).append("</ordNo>");
		if (rcvrNm != null) {
			xml.append("<rcvrNm>").append(rcvrNm).append("</rcvrNm>");
		}
		if (rcvrBaseAddr != null) {
			xml.append("<rcvrBaseAddr>").append(rcvrBaseAddr).append("</rcvrBaseAddr>");
		}
		xml.append("</order>");
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}
}

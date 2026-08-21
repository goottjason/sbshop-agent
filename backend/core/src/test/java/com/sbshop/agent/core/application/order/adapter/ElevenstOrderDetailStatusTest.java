package com.sbshop.agent.core.application.order.adapter;

import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Element;

@ExtendWith(MockitoExtension.class)
class ElevenstOrderDetailStatusTest {
	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	@Mock
	private ElevenstStatusMapper statusMapper;

	@Test
	@DisplayName("[D-031] 주문상세 파서는 상태를 SHIPPED로 조작하지 않는다")
	void fetchOrderDetail_doesNotFabricateShippedStatus() throws Exception {
		MarketCredential credential = Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		MarketOrderDto request = MarketOrderDto.builder().marketOrderNo("20260701000001").build();
		when(elevenstOrderApiPort.fetchOrderDetail("api-key", "20260701000001"))
			.thenReturn(List.of(detailElement("20260701000001")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		MarketOrderDto result = adapter.fetchOrderDetail(credential, request);

		assertThat(result).isNotNull();
		assertThat(result.getMarketOrderNo()).isEqualTo("20260701000001");
		assertThat(result.getStatus()).isNull();
	}

	private Element detailElement(String ordNo) throws Exception {
		String xml = "<order><ordNo>" + ordNo + "</ordNo><prdNm>테스트상품</prdNm></order>";
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}
}

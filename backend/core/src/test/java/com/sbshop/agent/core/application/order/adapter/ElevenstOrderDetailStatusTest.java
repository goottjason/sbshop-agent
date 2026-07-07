package com.sbshop.agent.core.application.order.adapter;

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

/**
 * D-031 회귀 방지: 11번가 주문상세(claimservice/orderlistalladdr) 파서가 배송 상태 필드가 없음에도
 * status를 SHIPPED로 하드코딩하던 결함을 고정한다. 이 엔드포인트는 상태를 제공하지 않으므로,
 * 파서는 상태를 조작하지 않고 미설정(null)으로 두어 enrichment 시 기존 상태를 덮어쓰지 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstOrderDetailStatusTest {

	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;
	@Mock
	private ElevenstStatusMapper statusMapper;

	private Element detailElement(String ordNo) throws Exception {
		String xml = "<order><ordNo>" + ordNo + "</ordNo><prdNm>테스트상품</prdNm></order>";
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	@Test
	@DisplayName("[D-031] 주문상세 파서는 상태를 SHIPPED로 조작하지 않는다")
	void fetchOrderDetail_doesNotFabricateShippedStatus() throws Exception {
		MarketCredential credential = org.mockito.Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		MarketOrderDto request = MarketOrderDto.builder().marketOrderNo("20260701000001").build();
		when(elevenstOrderApiPort.fetchOrderDetail("api-key", "20260701000001"))
			.thenReturn(List.of(detailElement("20260701000001")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		MarketOrderDto result = adapter.fetchOrderDetail(credential, request);

		assertThat(result).isNotNull();
		assertThat(result.getMarketOrderNo()).isEqualTo("20260701000001");
		// 상태 필드가 없는 엔드포인트이므로 상태를 조작해선 안 됨(null = 미설정, enrichment 시 no-clobber)
		assertThat(result.getStatus()).isNull();
	}
}

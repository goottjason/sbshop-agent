package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstNoticeInjectionWiringTest {

	private static final String GET_PATH = "/rest/prodmarketservice/prodmarket/PRD9";
	private static final String PUT_PATH = "/rest/prodservices/product/PRD9";

	private static final String XML_WITHOUT_NOTICE = "<?xml version=\"1.0\" encoding=\"euc-kr\" standalone=\"yes\"?>"
		+ "<Product><prdNo>PRD9</prdNo><prdNm><![CDATA[기존상품명]]></prdNm>"
		+ "<brand><![CDATA[기존브랜드]]></brand><makerNm><![CDATA[기존브랜드]]></makerNm>"
		+ "<dispCtgrNo>1127358</dispCtgrNo><ProductTag/><nResult>0</nResult></Product>";

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
	}

	private String capturePutXml() {
		when(restClient.get(eq(GET_PATH))).thenReturn(XML_WITHOUT_NOTICE);
		when(restClient.put(eq(PUT_PATH), anyString()))
			.thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

		Product p = org.mockito.Mockito.mock(Product.class);
		org.mockito.Mockito.lenient().when(p.getBrand()).thenReturn("새브랜드");
		Map<String, Object> raw = new HashMap<>();
		client.syncProductFields(p, "PRD9", raw, Set.of(MarketEditField.BRAND));

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(restClient).put(eq(PUT_PATH), body.capture());
		return body.getValue();
	}

	@Test
	@DisplayName("D-298: 정상 PUT(첫 시도 성공)에는 고시 블록을 넣지 않는다 "
		+ "— 통과 중인 1,681건의 마켓 저장 고시를 건드리지 않는다")
	void doesNotInjectNoticeOnSuccessfulPut() {
		String xml = capturePutXml();

		assertThat(xml).doesNotContain("ProductNotification");
		assertThat(xml).contains("<brand><![CDATA[새브랜드]]></brand>");
	}

	@Test
	@DisplayName("D-298: 고시 주입은 기존 필수필드 주입(주소코드·원산지·원재료)을 깨지 않는다")
	void keepsExistingRequiredFieldInjection() {
		String xml = capturePutXml();

		assertThat(xml).contains("<addrSeqOut>5</addrSeqOut>");
		assertThat(xml).contains("<addrSeqIn>3</addrSeqIn>");
		assertThat(xml).contains("<rmaterialTypCd>03</rmaterialTypCd>");
		assertThat(xml).contains("<ProductRmaterial>");
		assertThat(xml).contains("<orgnTypDtlsCd>1405</orgnTypDtlsCd>");
		assertThat(xml).doesNotContain("<nResult>");
	}
}

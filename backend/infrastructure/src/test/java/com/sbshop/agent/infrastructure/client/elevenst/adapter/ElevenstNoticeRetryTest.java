package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice.NoticeItem;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice.NoticeSpec;
import java.util.HashMap;
import java.util.List;
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
class ElevenstNoticeRetryTest {

	private static final String GET_PATH = "/rest/prodmarketservice/prodmarket/PRD9";
	private static final String PUT_PATH = "/rest/prodservices/product/PRD9";

	private static final String NOTICE_REJECT = "<ClientMessage><resultCode>0500</resultCode>"
		+ "<message>고시유형코드에 해당하는 고시 항목 개수가 일치하지 않습니다.</message></ClientMessage>";
	private static final String BANNED_WORD_REJECT = "<ClientMessage><resultCode>0500</resultCode>"
		+ "<message>상품명에 금칙어 [Solaray] 가 포함되어 있습니다.</message></ClientMessage>";
	private static final String OK = "<ClientMessage><resultCode>200</resultCode></ClientMessage>";

	private static final String CURRENT_XML = "<?xml version=\"1.0\" encoding=\"euc-kr\" standalone=\"yes\"?>"
		+ "<Product><prdNo>PRD9</prdNo><prdNm><![CDATA[기존상품명]]></prdNm>"
		+ "<brand><![CDATA[기존브랜드]]></brand><makerNm><![CDATA[기존브랜드]]></makerNm>"
		+ "<dispCtgrNo>1127358</dispCtgrNo><ProductTag/><nResult>0</nResult></Product>";

	private static final String XML_WITH_IMAGE = CURRENT_XML.replace("<ProductTag/>",
		"<prdImage01>http://old/rep.jpg</prdImage01><ProductTag/>");

	private static final NoticeSpec RESOLVED = new NoticeSpec("07", List.of(
		new NoticeItem("0701", "제품명"),
		new NoticeItem("0702", "내용량 및 원료명"),
		new NoticeItem("0703", "소비자상담 관련 전화번호")));

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient resolvedTableClient;
	private ElevenstMarketClient productionClient;

	@BeforeEach
	void setUp() {
		resolvedTableClient = new ElevenstMarketClient(restClient) {
			@Override
			NoticeSpec noticeSpec() {
				return RESOLVED;
			}
		};
		productionClient = new ElevenstMarketClient(restClient);
	}

	private Product brandProduct() {
		Product p = mock(Product.class);
		org.mockito.Mockito.lenient().when(p.getBrand()).thenReturn("새브랜드");
		return p;
	}

	private void syncBrand(ElevenstMarketClient client) {
		client.syncProductFields(brandProduct(), "PRD9", new HashMap<>(), Set.of(MarketEditField.BRAND));
	}

	private List<String> capturePutBodies(int times) {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(restClient, times(times)).put(eq(PUT_PATH), body.capture());
		return body.getAllValues();
	}

	@Test
	@DisplayName("D-298: 고시 개수 불일치로 거부되면 같은 XML 에 고시 블록을 주입해 1회만 재PUT 한다")
	void retriesOnceWithNoticeInjectedOnNoticeCountRejection() {
		when(restClient.get(eq(GET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PUT_PATH), anyString())).thenReturn(NOTICE_REJECT, OK);

		syncBrand(resolvedTableClient);

		List<String> bodies = capturePutBodies(2);
		assertThat(bodies.get(0)).doesNotContain("ProductNotification");
		assertThat(bodies.get(1)).contains("<ProductNotification><type>07</type>");
		assertThat(bodies.get(1)).contains("<item><code>0701</code>");
		assertThat(bodies.get(1)).contains("<brand><![CDATA[새브랜드]]></brand>");
	}

	@Test
	@DisplayName("D-298: 고시와 무관한 거부(금칙어)에는 재시도하지 않는다 — PUT 은 1회뿐")
	void doesNotRetryOnUnrelatedRejection() {
		when(restClient.get(eq(GET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PUT_PATH), anyString())).thenReturn(BANNED_WORD_REJECT);

		assertThatThrownBy(() -> syncBrand(resolvedTableClient))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("금칙어");

		verify(restClient, times(1)).put(eq(PUT_PATH), anyString());
	}

	@Test
	@DisplayName("D-298: 재PUT 도 실패하면 원래 거부 응답을 그대로 던진다 — 가짜성공 금지")
	void throwsOriginalRejectionWhenRetryAlsoFails() {
		when(restClient.get(eq(GET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PUT_PATH), anyString())).thenReturn(NOTICE_REJECT, NOTICE_REJECT);

		assertThatThrownBy(() -> syncBrand(resolvedTableClient))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("고시유형코드에 해당하는 고시 항목 개수");

		verify(restClient, times(2)).put(eq(PUT_PATH), anyString());
	}

	@Test
	@DisplayName("D-298: 코드표 확보 후에는 운영 기본 spec(건기식 891032)으로 재시도한다")
	void retriesWithProductionTableOnceResolved() {
		when(restClient.get(eq(GET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PUT_PATH), anyString())).thenReturn(NOTICE_REJECT, OK);

		syncBrand(productionClient);

		List<String> bodies = capturePutBodies(2);
		assertThat(bodies.get(0)).doesNotContain("ProductNotification");
		assertThat(bodies.get(1)).contains("<ProductNotification><type>891032</type>");
		assertThat(bodies.get(1).split("<item>", -1)).hasSize(14);
	}

	@Test
	@DisplayName("D-298: 이미지 재게시 경로도 같은 재시도 규칙을 탄다")
	void imagePathRetriesToo() {
		when(restClient.get(eq(GET_PATH))).thenReturn(XML_WITH_IMAGE);
		when(restClient.put(eq(PUT_PATH), anyString())).thenReturn(NOTICE_REJECT, OK);

		resolvedTableClient.syncImagesAndHtml(null, "PRD9", new HashMap<>(),
			List.of("http://new/rep.jpg"), null);

		List<String> bodies = capturePutBodies(2);
		assertThat(bodies.get(0)).doesNotContain("ProductNotification");
		assertThat(bodies.get(1)).contains("<ProductNotification><type>07</type>");
		assertThat(bodies.get(1)).contains("<prdImage01><![CDATA[http://new/rep.jpg]]></prdImage01>");
	}

	@Test
	@DisplayName("D-298: 첫 PUT 이 성공하면 재시도하지 않는다 — 정상 1,681건은 고시가 안 실린다")
	void noRetryWhenFirstPutSucceeds() {
		when(restClient.get(eq(GET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PUT_PATH), anyString())).thenReturn(OK);

		syncBrand(resolvedTableClient);

		List<String> bodies = capturePutBodies(1);
		assertThat(bodies.get(0)).doesNotContain("ProductNotification");
	}
}

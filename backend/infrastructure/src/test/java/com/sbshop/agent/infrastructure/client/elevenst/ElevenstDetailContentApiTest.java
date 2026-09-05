package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstDetailContentApiTest {

	private static final String DETAIL_PATH = "/rest/prodservices/updateProductDetailCont/PRD9";
	private static final String PRODMARKET_PATH = "/rest/prodmarketservice/prodmarket/PRD9";
	private static final String PRODUCT_PUT_PATH = "/rest/prodservices/product/PRD9";

	private static final String CURRENT_XML = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
		+ "<Product><prdNo>PRD9</prdNo>"
		+ "<prdNm><![CDATA[Solaray 비타민]]></prdNm>"
		+ "<prdImage01>http://old/rep.jpg</prdImage01>"
		+ "<htmlDetail><![CDATA[<p>마켓에 저장된 상세</p>]]></htmlDetail></Product>";

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;
	private Map<String, Object> raw;

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
		raw = new HashMap<>();
		raw.put("prdNo", "PRD9");
	}

	@Test
	@DisplayName("D-296: 상세설명은 전용 부분수정 API(POST updateProductDetailCont)로 보낸다")
	void sendsDetailThroughDedicatedApi() {
		when(restClient.post(eq(DETAIL_PATH), anyString())).thenReturn("<ProductDetailCont><resultCode>000</resultCode><message>상품 상세 내용이 수정되었습니다.</message></ProductDetailCont>");

		client.syncImagesAndHtml(null, "PRD9", raw, List.of(), "<p>새 상세</p>");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(restClient).post(eq(DETAIL_PATH), body.capture());
		String xml = body.getValue();
		assertThat(xml).contains("encoding=\"euc-kr\"");
		assertThat(xml).contains("<ProductDetailCont>");
		assertThat(xml).contains("<prdDescContClob><![CDATA[<p>새 상세</p>]]></prdDescContClob>");
		assertThat(xml).contains("</ProductDetailCont>");
	}

	@Test
	@DisplayName("D-296/D-299: 상세설명만 바꿀 때는 전체 XML 라운드트립을 아예 타지 않는다")
	void detailOnlyDoesNotRoundTripFullXml() {
		when(restClient.post(eq(DETAIL_PATH), anyString())).thenReturn("<ProductDetailCont><resultCode>000</resultCode><message>상품 상세 내용이 수정되었습니다.</message></ProductDetailCont>");

		client.syncImagesAndHtml(null, "PRD9", raw, List.of(), "<p>새 상세</p>");

		verify(restClient, never()).get(eq(PRODMARKET_PATH));
		verify(restClient, never()).put(eq(PRODUCT_PUT_PATH), anyString());
	}

	@Test
	@DisplayName("D-296: 상세설명 URL 은 https ai.esmplus.com 으로 승격해서 보낸다")
	void upgradesEsmplusImageHostInDetail() {
		when(restClient.post(eq(DETAIL_PATH), anyString())).thenReturn("<ProductDetailCont><resultCode>000</resultCode><message>상품 상세 내용이 수정되었습니다.</message></ProductDetailCont>");

		client.syncImagesAndHtml(null, "PRD9", raw, List.of(),
			"<img src=\"http://ai.esmplus.com/a.jpg\">");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(restClient).post(eq(DETAIL_PATH), body.capture());
		assertThat(body.getValue()).contains("https://ai.esmplus.com/a.jpg");
		assertThat(body.getValue()).doesNotContain("http://ai.esmplus.com");
	}

	@Test
	@DisplayName("D-296 라이브 실측: 성공 봉투는 ProductDetailCont+resultCode 000 — 000 아닌 resultCode 는 실패다")
	void throwsWhenResultCodeIsNotZeroZeroZero() {
		when(restClient.post(eq(DETAIL_PATH), anyString()))
			.thenReturn("<?xml version=\"1.0\" encoding=\"euc-kr\" standalone=\"yes\"?>"
				+ "<ProductDetailCont><resultCode>500</resultCode><message>수정 권한이 없습니다.</message></ProductDetailCont>");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "PRD9", raw, List.of(), "<p>새 상세</p>"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("수정 권한이 없습니다.");
	}

	@Test
	@DisplayName("D-296: 전용 API 가 오류 봉투(Products/message)를 주면 실패로 본다")
	void throwsWhenDedicatedApiReturnsErrorEnvelope() {
		when(restClient.post(eq(DETAIL_PATH), anyString()))
			.thenReturn("<Products><message>상세설명 수정 실패</message></Products>");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "PRD9", raw, List.of(), "<p>새 상세</p>"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("상세설명 수정 실패");
	}

	@Test
	@DisplayName("D-296: 전용 API 가 빈 응답이면 실패로 본다")
	void throwsWhenDedicatedApiReturnsBlank() {
		when(restClient.post(eq(DETAIL_PATH), anyString())).thenReturn("");

		assertThatThrownBy(() -> client.syncImagesAndHtml(null, "PRD9", raw, List.of(), "<p>새 상세</p>"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("D-299: 이미지까지 바꿔 전체 XML PUT 을 탈 때도 상세설명은 그 전문에 싣지 않는다 "
		+ "— 마켓에 저장된 htmlDetail 이 그대로 남아야 한다")
	void fullXmlPutKeepsMarketHtmlDetailUntouched() {
		when(restClient.post(eq(DETAIL_PATH), anyString())).thenReturn("<ProductDetailCont><resultCode>000</resultCode><message>상품 상세 내용이 수정되었습니다.</message></ProductDetailCont>");
		when(restClient.get(eq(PRODMARKET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PRODUCT_PUT_PATH), anyString()))
			.thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

		client.syncImagesAndHtml(null, "PRD9", raw, List.of("http://new/rep.jpg"), "<p>새 상세</p>");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(restClient).put(eq(PRODUCT_PUT_PATH), body.capture());
		String xml = body.getValue();
		assertThat(xml).contains("<htmlDetail><![CDATA[<p>마켓에 저장된 상세</p>]]></htmlDetail>");
		assertThat(xml).doesNotContain("<p>새 상세</p>");
		assertThat(xml).contains("<prdImage01><![CDATA[http://new/rep.jpg]]></prdImage01>");
	}

	@Test
	@DisplayName("D-296: 상세설명이 없으면 전용 API 를 호출하지 않는다 (이미지 경로만 탄다)")
	void skipsDedicatedApiWhenNoDetailHtml() {
		when(restClient.get(eq(PRODMARKET_PATH))).thenReturn(CURRENT_XML);
		when(restClient.put(eq(PRODUCT_PUT_PATH), anyString()))
			.thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

		client.syncImagesAndHtml(null, "PRD9", raw, List.of("http://new/rep.jpg"), null);

		verify(restClient, never()).post(eq(DETAIL_PATH), anyString());
	}
}

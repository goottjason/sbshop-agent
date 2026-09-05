package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.product.Product;
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
class ElevenstFieldSyncTest {

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;
	private Map<String, Object> raw;

	private static final String CURRENT_XML = "<?xml version=\"1.0\" encoding=\"euc-kr\"?>"
		+ "<Product><prdNo>PRD9</prdNo>"
		+ "<prdNm><![CDATA[기존상품명]]></prdNm>"
		+ "<brand><![CDATA[기존브랜드]]></brand>"
		+ "<makerNm><![CDATA[기존브랜드]]></makerNm>"
		+ "<dispCtgrNo>1012345</dispCtgrNo></Product>";

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
		raw = new HashMap<>();
		raw.put("prdNo", "PRD9");
	}

	private Product product(String productName, String brand) {
		Product p = mock(Product.class);
		if (productName != null) {
			org.mockito.Mockito.lenient().when(p.getProductName()).thenReturn(productName);
		}
		if (brand != null) {
			org.mockito.Mockito.lenient().when(p.getBrand()).thenReturn(brand);
		}
		return p;
	}

	@Test
	@DisplayName("D-294: prdNm 태그만 치환한다 — 다른 태그(brand/makerNm/dispCtgrNo)는 불변이다")
	void replacesOnlyProductNameTag() {
		String result = ElevenstMarketClient.replaceXmlCdataField(CURRENT_XML, "prdNm", "새상품명");

		assertThat(result).contains("<prdNm><![CDATA[새상품명]]></prdNm>");
		assertThat(result).contains("<brand><![CDATA[기존브랜드]]></brand>");
		assertThat(result).contains("<makerNm><![CDATA[기존브랜드]]></makerNm>");
		assertThat(result).contains("<dispCtgrNo>1012345</dispCtgrNo>");
	}

	@Test
	@DisplayName("D-294: PRODUCT_NAME/BRAND/MANUFACTURER 3태그를 동시에 치환한다 (제조사=makerNm, 값은 brand 출처)")
	void replacesThreeFieldsAtOnce() {
		when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn(CURRENT_XML);
		when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
			.thenReturn("<ClientMessage><resultCode>200</resultCode></ClientMessage>");

		client.syncProductFields(product("새상품명", "새브랜드"), "PRD9", raw,
			Set.of(MarketEditField.PRODUCT_NAME, MarketEditField.BRAND, MarketEditField.MANUFACTURER));

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(restClient).put(eq("/rest/prodservices/product/PRD9"), body.capture());
		String xml = body.getValue();
		assertThat(xml).contains("<prdNm><![CDATA[새상품명]]></prdNm>");
		assertThat(xml).contains("<brand><![CDATA[새브랜드]]></brand>");
		assertThat(xml).contains("<makerNm><![CDATA[새브랜드]]></makerNm>");
	}

	@Test
	@DisplayName("D-294: null/blank 값인 필드는 기존 태그값을 덮어쓰지 않는다")
	void doesNotOverwriteWithNullOrBlank() {
		assertThat(ElevenstMarketClient.replaceXmlCdataField(CURRENT_XML, "brand", null))
			.isEqualTo(CURRENT_XML);
		assertThat(ElevenstMarketClient.replaceXmlCdataField(CURRENT_XML, "brand", "  "))
			.isEqualTo(CURRENT_XML);
	}

	@Test
	@DisplayName("D-294: 치환된 태그는 CDATA 래핑을 유지한다")
	void keepsCdataWrapping() {
		String result = ElevenstMarketClient.replaceXmlCdataField(CURRENT_XML, "makerNm", "새제조사");

		assertThat(result).containsPattern("<makerNm><!\\[CDATA\\[새제조사\\]\\]></makerNm>");
	}

	@Test
	@DisplayName("D-294: PUT 응답 오류(resultCode 200/210 아님) 시 RuntimeException")
	void throwsOnErrorResponse() {
		when(restClient.get(eq("/rest/prodmarketservice/prodmarket/PRD9"))).thenReturn(CURRENT_XML);
		when(restClient.put(eq("/rest/prodservices/product/PRD9"), anyString()))
			.thenReturn("<ClientMessage><resultCode>500</resultCode></ClientMessage>");

		assertThatThrownBy(() -> client.syncProductFields(product("새상품명", "새브랜드"), "PRD9", raw,
			Set.of(MarketEditField.PRODUCT_NAME)))
			.isInstanceOf(RuntimeException.class);
	}
}

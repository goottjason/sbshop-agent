package com.sbshop.agent.infrastructure.client.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MarketItemExtractionTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("스마트스토어 실제 name·가격·이미지·상세 필드를 비교 DTO로 추출한다")
	void smartstoreActualContract() {
		var info = client("SMARTSTORE", """
			{"originProduct":{"id":123,"name":"실제 상품명","salePrice":33600,"stockQuantity":300,
			"detailContent":"<p>상세</p>","images":{"representativeImage":{"url":"https://img/main.jpg"},
			"optionalImages":[{"url":"https://img/extra.jpg"}]}}}
			""").extractMarketItem("123");
		assertThat(info.name()).isEqualTo("실제 상품명");
		assertThat(info.salePrice()).isEqualByComparingTo("33600");
		assertThat(info.stock()).isEqualTo(300);
		assertThat(info.detailHtml()).isEqualTo("<p>상세</p>");
		assertThat(info.images()).containsExactly("https://img/main.jpg", "https://img/extra.jpg");
	}

	@Test
	@DisplayName("스마트스토어 미제공 가격·재고는 0으로 꾸미지 않고 미확인으로 남긴다")
	void smartstoreMissingNumbersStayUnknownAndLocalNameUsesActualKey() {
		var client = client("SMARTSTORE", "{\"originProduct\":{\"id\":123,\"name\":\"상품\"}}");
		var info = client.extractMarketItem("123");
		assertThat(info.salePrice()).isNull();
		assertThat(info.stock()).isNull();
		assertThat(client.parseLocalData(Map.of("name", "저장된 상품명")).name()).isEqualTo("저장된 상품명");
	}

	@Test
	@DisplayName("카페24 문자열 판매가와 대표이미지는 비교 DTO에서 누락하지 않는다")
	void cafe24ActualContract() {
		var info = client("CAFE24", """
			{"product":{"product_no":123,"product_code":"P00000AB","product_name":"카페 상품",
			"price":"37800.00","description":"<p>상세</p>","detail_image":"https://img/cafe.jpg"}}
			""").extractMarketItem("123");
		assertThat(info.salePrice()).isEqualByComparingTo("37800.00");
		assertThat(info.images()).contains("https://img/cafe.jpg");
		assertThat(info.stock()).isNull();
	}

	@Test
	@DisplayName("쿠팡 단일 옵션 판매가·이미지·HTML을 추출하고 구매제한을 실재고로 반환하지 않는다")
	void coupangActualContract() {
		var info = client("COUPANG", """
			{"code":"SUCCESS","data":{"sellerProductId":123,"displayProductName":"쿠팡 상품",
			"items":[{"vendorItemId":456,"salePrice":39000,"maximumBuyCount":999,
			"images":[{"imageOrder":0,"imageType":"REPRESENTATION","vendorPath":"https://img/coupang.jpg"}],
			"contents":[{"contentsType":"HTML","contentDetails":[{"detailType":"TEXT","content":"<p>상세</p>"}]}]}]}}
			""").extractMarketItem("123");
		assertThat(info.salePrice()).isEqualByComparingTo("39000");
		assertThat(info.images()).containsExactly("https://img/coupang.jpg");
		assertThat(info.detailHtml()).isEqualTo("<p>상세</p>");
		assertThat(info.stock()).isNull();
	}

	@Test
	@DisplayName("11번가는 확인된 상세 GET 경로를 쓰고 namespace·CDATA 응답을 읽는다")
	void elevenstActualDetailContract() {
		var rest = mock(ElevenstMarketRestClient.class);
		when(rest.get("/rest/prodmarketservice/prodmarket/123")).thenReturn("""
			<ns2:Product><ns2:prdNo>123</ns2:prdNo><ns2:prdNm><![CDATA[이름 & 상품]]></ns2:prdNm>
			<ns2:selPrc>42000</ns2:selPrc><ns2:htmlDetail><![CDATA[<p>상세</p>]]></ns2:htmlDetail>
			<ns2:prdImage01>https://img/11st.jpg</ns2:prdImage01></ns2:Product>
			""");
		var info = new ElevenstMarketClient(rest).extractMarketItem("123");
		assertThat(info.name()).isEqualTo("이름 & 상품");
		assertThat(info.salePrice()).isEqualByComparingTo("42000");
		assertThat(info.detailHtml()).isEqualTo("<p>상세</p>");
		assertThat(info.images()).containsExactly("https://img/11st.jpg");
		verify(rest).get("/rest/prodmarketservice/prodmarket/123");
	}

	@ParameterizedTest
	@CsvSource(value = {
		"SMARTSTORE|{\"code\":\"NOT_FOUND\",\"message\":\"조회 실패\"}",
		"CAFE24|{\"error\":{\"code\":401,\"message\":\"조회 실패\"}}",
		"COUPANG|{\"code\":\"ERROR\",\"message\":\"조회 실패\",\"data\":{}}",
		"ELEVENST|<AuthMessage><resultCode>-997</resultCode><resultMessage>등록된 API 정보가 존재하지 않습니다.</resultMessage></AuthMessage>",
		"SMARTSTORE|{\"originProduct\":{}}",
		"CAFE24|{\"product\":{}}",
		"COUPANG|{\"code\":\"SUCCESS\",\"data\":{}}",
		"ELEVENST|<Product><message>상품이 없습니다</message><nResult>0</nResult></Product>"
	}, delimiter = '|')
	@DisplayName("HTTP200 업무 오류 및 빈 상품은 성공 DTO가 아니라 오류로 전달한다")
	void rejectsBusinessErrorsAndEmptyProducts(String market, String body) {
		assertThatThrownBy(() -> client(market, body).extractMarketItem("123"))
			.isInstanceOf(RuntimeException.class);
	}

	@ParameterizedTest
	@CsvSource(value = {
		"SMARTSTORE|{\"originProduct\":{\"id\":999,\"name\":\"다른 상품\"}}",
		"CAFE24|{\"product\":{\"product_no\":999,\"product_name\":\"다른 상품\"}}",
		"COUPANG|{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":999}}",
		"ELEVENST|<Product><prdNo>999</prdNo><prdNm>다른 상품</prdNm></Product>"
	}, delimiter = '|')
	@DisplayName("마켓 응답 상품번호가 조회 대상과 다르면 다른 상품을 비교값으로 반환하지 않는다")
	void rejectsDifferentProduct(String market, String body) {
		assertThatThrownBy(() -> client(market, body).extractMarketItem("123"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("쿠팡 여러 옵션의 첫 가격·이미지를 상품 전체의 값으로 확정하지 않는다")
	void coupangMultipleOptionsStayUnresolved() {
		var info = client("COUPANG", """
			{"code":"SUCCESS","data":{"sellerProductId":123,"displayProductName":"옵션 상품",
			"items":[{"salePrice":10000,"maximumBuyCount":999,
			"images":[{"imageOrder":0,"vendorPath":"https://img/a.jpg"}]},{"salePrice":20000}]}}
			""").extractMarketItem("123");
		assertThat(info.name()).isEqualTo("옵션 상품");
		assertThat(info.salePrice()).isNull();
		assertThat(info.stock()).isNull();
		assertThat(info.images()).isNull();
	}

	private MarketClient client(String market, String response) {
		return switch (market) {
			case "SMARTSTORE" -> {
				var rest = mock(SmartstoreRestClient.class);
				when(rest.get(anyString())).thenReturn(response);
				yield new SmartstoreMarketClient(null, null, null, null, rest, mapper);
			}
			case "CAFE24" -> {
				var rest = mock(Cafe24RestClient.class);
				when(rest.get(anyString())).thenReturn(response);
				yield new Cafe24MarketClient(mapper, rest, new HtmlImageExtractor(), null, null, null);
			}
			case "COUPANG" -> {
				var rest = mock(CoupangRestClient.class);
				when(rest.get(anyString())).thenReturn(response);
				yield new CoupangMarketClient(null, mapper, rest, null, new CoupangProductParser(mapper),
					null, new CoupangDataMapper(mapper), null, null);
			}
			case "ELEVENST" -> {
				var rest = mock(ElevenstMarketRestClient.class);
				when(rest.get(anyString())).thenReturn(response);
				yield new ElevenstMarketClient(rest);
			}
			default -> throw new IllegalArgumentException(market);
		};
	}
}

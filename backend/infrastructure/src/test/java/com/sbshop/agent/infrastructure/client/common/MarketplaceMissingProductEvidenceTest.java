package com.sbshop.agent.infrastructure.client.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/** Live GET envelopes captured 2026-09-08; malformed, ambiguous and wrong-account reads keep links. */
class MarketplaceMissingProductEvidenceTest {

	private static final String CP_PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/123";
	private static final String CP_ABSENT = "{\"code\":\"DEFAULT\",\"message\":\"Product(123) data not found.\"}";
	private static final String ELEVEN_PATH = "/rest/prodmarketservice/prodmarket/123";
	private static final String ELEVEN_MESSAGE = "[123] 상품 정보 조회중 오류입니다.해당 상품의 정보를 찾을 수 없습니다. 상품번호 : 123";
	private static final String ELEVEN_ABSENT = "<Product><message>" + ELEVEN_MESSAGE
		+ "</message><validateMsg></validateMsg><shopNo>0</shopNo><prdStckQty>0</prdStckQty><nResult>0</nResult></Product>";
	private static final String CAFE_DETAIL = "/admin/products/123?shop_no=1";
	private static final String CAFE_LIST = "/admin/products?shop_no=1&product_no=123&fields=product_no,shop_no,custom_product_code&limit=2";

	@Test
	void coupangExactHttp400ForRequestedIdConfirmsAbsenceThroughTransportWrapper() {
		var rest = coupangRest();
		when(rest.get(CP_PATH)).thenThrow(
			new RuntimeException("transport wrapper", http(400, CP_ABSENT, "application/json;charset=UTF-8")));
		var observed = coupang(rest).inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.DELETED);
		assertThat(observed.code()).isEqualTo("PRODUCT_ABSENT/COUPANG_NOT_FOUND");
		assertThat(observed.accountReference()).isEqualTo(MarketApiEvidence.account("COUPANG", "A1"));
		assertThat(observed.endpoint()).isEqualTo("GET " + CP_PATH);
		verify(rest).get(CP_PATH);
		verify(rest, times(2)).resolveVendorId();
		verifyNoMoreInteractions(rest);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 404, 429, 500, 502})
	void coupangOtherHttpStatusEvenWithExactMessageCannotConfirmAbsence(int status) {
		var rest = coupangRest();
		when(rest.get(CP_PATH)).thenThrow(http(status, CP_ABSENT, "application/json"));
		var observed = coupang(rest).inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.UNKNOWN);
		assertThat(observed.code()).isEqualTo("HTTP_" + status);
		if (status == 429) {
			assertThat(observed.rateLimited()).isTrue();
			assertThat(observed.retryAfter()).isAfter(Instant.now().plusSeconds(590));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/html", "", "invalid media type"})
	void coupangMissingOrInvalidJsonContentTypeCannotConfirmAbsence(String contentType) {
		var rest = coupangRest();
		when(rest.get(CP_PATH)).thenThrow(http(400, CP_ABSENT, contentType));
		assertThat(coupang(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	static Stream<String> ambiguousCoupangBodies() {
		return Stream.of(CP_ABSENT.replace("123", "999"), CP_ABSENT.replace("DEFAULT", "ERROR"),
			CP_ABSENT.replace("found.", "found"), CP_ABSENT.replace("}", ",\"data\":null}"),
			CP_ABSENT.replace("\"DEFAULT\"", "\"ERROR\",\"code\":\"DEFAULT\""), CP_ABSENT + "{}",
			"", "<html>Product(123) data not found.</html>", "[]", "null");
	}

	@ParameterizedTest
	@MethodSource("ambiguousCoupangBodies")
	void coupangAmbiguousEnvelopeCannotConfirmAbsence(String body) {
		var rest = coupangRest();
		when(rest.get(CP_PATH)).thenThrow(http(400, body, "application/json"));
		assertThat(coupang(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void coupangSuccessfulTransportWithFailureEnvelopeAndAccountChangeKeepConnection() {
		var rest = coupangRest();
		when(rest.get(CP_PATH)).thenReturn(CP_ABSENT);
		assertThat(coupang(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		doThrow(http(400, CP_ABSENT, "application/json")).when(rest).get(CP_PATH);
		when(rest.resolveVendorId()).thenReturn("A1", "A2");
		assertThat(coupang(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void elevenstExactBusinessResponseForRequestedIdConfirmsAbsence() {
		var rest = elevenstRest();
		when(rest.requestStrict("GET", ELEVEN_PATH, null)).thenReturn(ELEVEN_ABSENT);
		var observed = new ElevenstMarketClient(rest).inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.DELETED);
		assertThat(observed.code()).isEqualTo("PRODUCT_ABSENT/ELEVENST_NOT_FOUND");
		assertThat(observed.accountReference()).isEqualTo("seller-A");
		verify(rest).requestStrict("GET", ELEVEN_PATH, null);
		verify(rest, times(2)).accountReference();
		verifyNoMoreInteractions(rest);
	}

	static Stream<String> ambiguousElevenstBodies() {
		return Stream.of(ELEVEN_ABSENT.replace("[123]", "[999]"), ELEVEN_ABSENT.replace("번호 : 123", "번호 : 999"),
			ELEVEN_ABSENT.replace(ELEVEN_MESSAGE, "상품이 없습니다."),
			ELEVEN_ABSENT.replace("<nResult>0</nResult>", ""), ELEVEN_ABSENT.replace("<nResult>0", "<nResult>1"),
			ELEVEN_ABSENT.replace("<validateMsg></validateMsg>", "<validateMsg>접근 불가</validateMsg>"),
			ELEVEN_ABSENT.replace("</Product>", "<resultCode>403</resultCode></Product>"),
			ELEVEN_ABSENT.replace("</Product>", "<prdNo>123</prdNo></Product>"),
			ELEVEN_ABSENT.replace("</Product>", "<selStatCd>103</selStatCd></Product>"),
			ELEVEN_ABSENT.replace("</Product>", "<sellerPrdCd>SB123</sellerPrdCd></Product>"),
			ELEVEN_ABSENT.replace("</Product>", "<message>" + ELEVEN_MESSAGE + "</message></Product>"),
			ELEVEN_ABSENT.replace("</Product>", "<nResult>0</nResult></Product>"),
			ELEVEN_ABSENT.replace("Product>", "ClientMessage>"));
	}

	@ParameterizedTest
	@MethodSource("ambiguousElevenstBodies")
	void elevenstMismatchedAmbiguousOrContradictoryResponseCannotConfirmAbsence(String body) {
		var rest = elevenstRest();
		when(rest.requestStrict("GET", ELEVEN_PATH, null)).thenReturn(body);
		assertThat(new ElevenstMarketClient(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void elevenstHttp404ContainingBusinessResponseAndAccountChangeCannotConfirmAbsence() {
		var rest = elevenstRest();
		when(rest.requestStrict("GET", ELEVEN_PATH, null)).thenThrow(http(404, ELEVEN_ABSENT, "application/xml"));
		assertThat(new ElevenstMarketClient(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		doReturn(ELEVEN_ABSENT).when(rest).requestStrict("GET", ELEVEN_PATH, null);
		when(rest.accountReference()).thenReturn("seller-A", "seller-B");
		assertThat(new ElevenstMarketClient(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void cafe24RequiresBothDetailAndExactFilteredListToConfirmAbsence() {
		var rest = cafeRest();
		when(rest.get(CAFE_DETAIL)).thenReturn("{\"product\":{}}");
		when(rest.get(CAFE_LIST)).thenReturn("{\"products\":[]}");
		var observed = cafe(rest).inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.DELETED);
		assertThat(observed.code()).isEqualTo("PRODUCT_ABSENT/CAFE24_EMPTY_PRODUCT_AND_FILTERED_LIST");
		assertThat(observed.endpoint()).contains("GET " + CAFE_DETAIL, "GET " + CAFE_LIST);
		var order = inOrder(rest);
		order.verify(rest).accountReference();
		order.verify(rest).get(CAFE_DETAIL);
		order.verify(rest).accountReference();
		order.verify(rest).get(CAFE_LIST);
		order.verify(rest).accountReference();
		verifyNoMoreInteractions(rest);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{}", "{\"product\":null}", "<html>404</html>", "{\"product\":{},\"error\":404}",
		"{\"product\":{},\"product\":{}}", "{\"product\":{}}{}"})
	void cafe24AmbiguousDetailDoesNotRunConfirmationOrDetach(String body) {
		var rest = cafeRest();
		when(rest.get(CAFE_DETAIL)).thenReturn(body);
		assertThat(cafe(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		verify(rest, never()).get(CAFE_LIST);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{}", "{\"products\":null}", "{\"product\":[]}", "{\"products\":{},\"error\":404}",
		"{\"products\":[{\"product_no\":123,\"shop_no\":1}]}", "{\"products\":[{\"product_no\":999,\"shop_no\":1}]}",
		"{\"products\":[],\"error\":null}", "{\"products\":[],\"products\":[]}", "{\"products\":[]}{}"})
	void cafe24MissingNonemptyOrMalformedConfirmationKeepsConnection(String body) {
		var rest = cafeRest();
		when(rest.get(CAFE_DETAIL)).thenReturn("{\"product\":{}}");
		when(rest.get(CAFE_LIST)).thenReturn(body);
		assertThat(cafe(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void cafe24Detail404DoesNotRunSecondReadAndConfirmation429KeepsRetryAfter() {
		var rest = cafeRest();
		when(rest.get(CAFE_DETAIL)).thenThrow(http(404, "{\"product\":{}}", "application/json"));
		assertThat(cafe(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		verify(rest, never()).get(CAFE_LIST);
		doReturn("{\"product\":{}}").when(rest).get(CAFE_DETAIL);
		when(rest.get(CAFE_LIST)).thenThrow(http(429, "{\"products\":[]}", "application/json"));
		var observed = cafe(rest).inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.UNKNOWN);
		assertThat(observed.rateLimited()).isTrue();
		assertThat(observed.retryAfter()).isAfter(Instant.now().plusSeconds(590));
	}

	@Test
	void cafe24AccountChangeBeforeOrAfterConfirmationKeepsConnection() {
		var rest = cafeRest();
		when(rest.get(CAFE_DETAIL)).thenReturn("{\"product\":{}}");
		when(rest.get(CAFE_LIST)).thenReturn("{\"products\":[]}");
		when(rest.accountReference()).thenReturn("mall-A", "mall-B");
		assertThat(cafe(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		verify(rest, never()).get(CAFE_LIST);
		when(rest.accountReference()).thenReturn("mall-A", "mall-A", "mall-B");
		assertThat(cafe(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		verify(rest).get(CAFE_LIST);
	}

	@ParameterizedTest
	@ValueSource(strings = {"T", "F"})
	void cafe24ExistingProductPreservesSellingStateWithoutAbsenceListRead(String selling) {
		var rest = cafeRest();
		when(rest.get(CAFE_DETAIL))
			.thenReturn("{\"product\":{\"product_no\":123,\"shop_no\":1,\"selling\":\"" + selling + "\"}}");
		assertThat(cafe(rest).inspectListing("123").state())
			.isEqualTo("T".equals(selling) ? State.PRESENT : State.STOPPED);
		verify(rest, never()).get(CAFE_LIST);
	}

	private CoupangRestClient coupangRest() {
		var rest = mock(CoupangRestClient.class);
		when(rest.resolveVendorId()).thenReturn("A1");
		return rest;
	}

	private CoupangMarketClient coupang(CoupangRestClient rest) {
		return new CoupangMarketClient(null, new ObjectMapper(), rest, null, null, null, null, null, null);
	}

	private ElevenstMarketRestClient elevenstRest() {
		var rest = mock(ElevenstMarketRestClient.class);
		when(rest.accountReference()).thenReturn("seller-A");
		return rest;
	}

	private Cafe24RestClient cafeRest() {
		var rest = mock(Cafe24RestClient.class);
		when(rest.accountReference()).thenReturn("mall-A");
		return rest;
	}

	private Cafe24MarketClient cafe(Cafe24RestClient rest) {
		return new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null);
	}

	private RuntimeException http(int status, String body, String contentType) {
		var headers = new HttpHeaders();
		if (!contentType.isEmpty())
			headers.add(HttpHeaders.CONTENT_TYPE, contentType);
		if (status == 429)
			headers.set("Retry-After", "600");
		return new RestClientResponseException("remote response", status, "response", headers,
			body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}
}

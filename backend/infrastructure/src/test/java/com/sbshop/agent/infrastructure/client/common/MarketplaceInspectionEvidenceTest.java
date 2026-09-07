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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;

class MarketplaceInspectionEvidenceTest {
	@ParameterizedTest
	@CsvSource({"103,PRESENT", "104,OUT_OF_STOCK", "105,STOPPED", "106,STOPPED", "108,PROHIBITED", "999,UNKNOWN"})
	void elevenstUsesDocumentedProductEndpointAndExplicitState(String status, State expected) {
		var rest = mock(ElevenstMarketRestClient.class);
		when(rest.accountReference()).thenReturn("seller-A");
		when(rest.requestStrict("GET", "/rest/prodmarketservice/prodmarket/123", null))
			.thenReturn("<ns2:Product><prdNo>123</prdNo><selStatCd>" + status + "</selStatCd></ns2:Product>");
		assertThat(new ElevenstMarketClient(rest).inspectListing("123").state()).isEqualTo(expected);
		verify(rest, never()).get(any());
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {
		"<ClientMessage><resultCode>500</resultCode><message>상품이 없습니다.</message></ClientMessage>",
		"<Product><prdNo>999</prdNo><selStatCd>108</selStatCd></Product>",
		"<Product><prdNo>123</prdNo><prdNo>999</prdNo><selStatCd>108</selStatCd></Product>",
		"<!DOCTYPE Product [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><Product><prdNo>123</prdNo><selStatCd>&x;</selStatCd></Product>",
		"", "<html>404</html>"})
	void elevenstErrorsAndMismatchedOrUnsafeXmlNeverDetach(String response) {
		var rest = mock(ElevenstMarketRestClient.class);
		when(rest.accountReference()).thenReturn("seller-A");
		when(rest.requestStrict(any(), any(), any())).thenReturn(response);
		assertThat(new ElevenstMarketClient(rest).inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void cafe24SellingFalseIsNotPermanentBanAndNeverAppliesToChildMarkets() {
		var rest = mock(Cafe24RestClient.class);
		when(rest.accountReference()).thenReturn("mall-A");
		when(rest.get(any())).thenReturn("{\"product\":{\"product_no\":123,\"shop_no\":1,\"selling\":\"F\"}}");
		var observed = new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null).inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.STOPPED);
		assertThat(observed.detail()).contains("G마켓·옥션", "별도");
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "{\"product\":{}}",
		"{\"error\":{\"code\":404}}", "{\"product\":{\"product_no\":999,\"shop_no\":1,\"selling\":\"F\"}}",
		"{\"product\":{\"product_no\":123,\"shop_no\":2,\"selling\":\"T\"}}"})
	void cafe24RequiresExactShopAndProduct(String response) {
		var rest = mock(Cafe24RestClient.class);
		when(rest.accountReference()).thenReturn("mall-A");
		when(rest.get(any())).thenReturn(response);
		assertThat(
			new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null).inspectListing("123").state())
			.isEqualTo(State.UNKNOWN);
	}

	@Test
	void rateLimitedResponseKeepsRetryAfterAnd404AloneIsNeverAbsence() {
		var rest = mock(Cafe24RestClient.class);
		when(rest.accountReference()).thenReturn("mall-A");
		var headers = new HttpHeaders();
		headers.set("Retry-After", "600");
		when(rest.get(any())).thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limit", headers,
			new byte[0], StandardCharsets.UTF_8));
		var client = new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null);
		var observed = client.inspectListing("123");
		assertThat(observed.state()).isEqualTo(State.UNKNOWN);
		assertThat(observed.retryAfter()).isAfter(Instant.now().plusSeconds(590));
		assertThat(observed.rateLimited()).isTrue();
		doThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND)).when(rest).get(any());
		assertThat(client.inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}

	@Test
	void coupangOnlyExplicitProductDeletionForExactSellerAndProductCanDetach() {
		var rest = mock(CoupangRestClient.class);
		when(rest.resolveVendorId()).thenReturn("A1");
		var client = new CoupangMarketClient(null, new ObjectMapper(), rest, null, null, null, null, null, null);
		when(rest.get(any())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":123,\"vendorId\":\"A1\",\"statusName\":\"상품삭제\"}}");
		assertThat(client.inspectListing("123").state()).isEqualTo(State.DELETED);
		assertThat(client.inspectListing("999").state()).isEqualTo(State.UNKNOWN);
		when(rest.resolveVendorId()).thenReturn("A2");
		assertThat(client.inspectListing("123").state()).isEqualTo(State.UNKNOWN);
		when(rest.get(any())).thenReturn("{\"code\":\"ERROR\",\"message\":\"Product(123) data not found\"}");
		assertThat(client.inspectListing("123").state()).isEqualTo(State.UNKNOWN);
	}
}

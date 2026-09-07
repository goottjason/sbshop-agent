package com.sbshop.agent.infrastructure.client.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;

class MarketPriceContractTest {
	@Test
	void naverUsesPriceOnlyPatchAndFreshOriginRead() {
		var rest = mock(SmartstoreRestClient.class);
		when(rest.accountReference()).thenReturn("account-A");
		var client = new SmartstoreMarketClient(null, null, null, null, rest, new ObjectMapper());
		when(rest.get("/v2/products/origin-products/123"))
			.thenReturn("{\"originProduct\":{\"id\":123,\"statusType\":\"SALE\",\"salePrice\":12300}}");
		assertThat(client.readSalePrice("123", null).value()).isEqualByComparingTo("12300");
		client.writeSalePrice("123", null, new BigDecimal("12300"));
		var body = ArgumentCaptor.forClass(Object.class);
		verify(rest).patch(eq("/v1/products/origin-products/multi-update"), body.capture());
		var node = new ObjectMapper().valueToTree(body.getValue());
		assertThat(node.toString()).isEqualTo(new ObjectMapper()
			.valueToTree(Map.of("multiProductUpdateRequestVos", List.of(Map.of("originProductNo", 123L,
				"multiUpdateTypes", List.of("SALE_PRICE"), "productSalePrice", Map.of("salePrice", 12300)))))
			.toString());
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"originProduct\":{\"id\":999,\"statusType\":\"SALE\",\"salePrice\":12300}}",
		"{\"code\":\"ERROR\",\"originProduct\":{\"salePrice\":12300}}",
		"{\"originProduct\":{\"statusType\":\"SALE\"}}"})
	void naverMissingOrWrongProductIsNeverDefaultPrice(String response) {
		var rest = mock(SmartstoreRestClient.class);
		when(rest.accountReference()).thenReturn("account-A");
		when(rest.get(any())).thenReturn(response);
		var client = new SmartstoreMarketClient(null, null, null, null, rest, new ObjectMapper());
		assertThatThrownBy(() -> client.readSalePrice("123", null)).isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void naverStoppedProductCanBeReadButCannotBeWrittenByWorker() {
		var rest = mock(SmartstoreRestClient.class);
		when(rest.accountReference()).thenReturn("account-A");
		when(rest.get(any()))
			.thenReturn("{\"originProduct\":{\"id\":123,\"statusType\":\"PROHIBITION\",\"salePrice\":12300}}");
		assertThat(new SmartstoreMarketClient(null, null, null, null, rest, new ObjectMapper())
			.readSalePrice("123", null).writable()).isFalse();
	}

	@Test
	void cafe24MainPriceIsSeparateAndMarketPlusFlagIsPreserved() {
		var rest = mock(Cafe24RestClient.class);
		when(rest.accountReference()).thenReturn("account-A");
		var client = new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null);
		when(rest.get(any())).thenReturn(
			"{\"product\":{\"product_no\":123,\"shop_no\":1,\"price\":\"12300.00\",\"selling\":\"T\",\"market_sync\":\"T\"}}");
		assertThat(client.readSalePrice("123", null).writable()).isFalse();
		when(rest.get(any())).thenReturn(
			"{\"product\":{\"product_no\":123,\"shop_no\":1,\"price\":\"12300.00\",\"selling\":\"T\",\"market_sync\":\"F\",\"tax_calculation\":\"A\"}}");
		assertThat(client.readSalePrice("123", null).writable()).isTrue();
		client.writeSalePrice("123", null, new BigDecimal("12300"));
		verify(rest).put("/admin/products/123", Map.of("shop_no", 1, "request", Map.of("price", 12300)));
	}

	@Test
	void coupangVerifiesParentAndOptionThenReadsActualOptionSalePrice() {
		var rest = mock(CoupangRestClient.class);
		when(rest.resolveVendorId()).thenReturn("A1");
		var client = new CoupangMarketClient(null, new ObjectMapper(), rest, null, null, null, null, null, null);
		when(rest.get(contains("seller-products/"))).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":123,\"vendorId\":\"A1\",\"statusName\":\"승인완료\",\"items\":[{\"vendorItemId\":456}]}}");
		when(rest.get(contains("/inventories")))
			.thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"salePrice\":12300,\"onSale\":true}}");
		assertThat(client.readSalePrice("123", "456").value()).isEqualByComparingTo("12300");
		assertThatThrownBy(() -> client.readSalePrice("123", "789")).isInstanceOf(MarketTransferFailure.class);
		client.writeSalePrice("123", "456", new BigDecimal("12300"));
		verify(rest).put("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/456/prices/12300", null);
	}

	@Test
	void errorCarries429RetryAfterWithoutRawResponse() {
		var headers = new HttpHeaders();
		headers.set("Retry-After", "600");
		var e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limit", headers,
			"{\"message\":\"요청 초과\",\"access_token\":\"private-token\"}"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8),
			java.nio.charset.StandardCharsets.UTF_8);
		var failure = MarketApiEvidence.transferFailure(e);
		assertThat(failure.rateLimited()).isTrue();
		assertThat(failure.getRetryAfter()).isAfter(java.time.Instant.now().plusSeconds(590));
		assertThat(failure.getMessage()).contains("요청 초과").doesNotContain("private-token");
	}

	@Test
	void preparedPublicationSendsExactFrozenRequestAndVerifiesSbCodeAndCoreFields() throws Exception {
		var mapper = new ObjectMapper();
		var rest = mock(SmartstoreRestClient.class);
		var client = new SmartstoreMarketClient(null, null, null, null, rest, mapper);
		String payload = "{\"originProduct\":{\"name\":\"상품\",\"leafCategoryId\":\"50001\",\"salePrice\":12300,\"stockQuantity\":300,\"detailContent\":\"html\",\"images\":{\"representativeImage\":{\"url\":\"https://example.com/1.jpg\"}},\"detailAttribute\":{\"sellerCodeInfo\":{\"sellerManagementCode\":\"SB-123\"}}}}";
		when(rest.post(eq("/v2/products"), any()))
			.thenReturn("{\"originProductNo\":456,\"smartstoreChannelProductNo\":789}");
		assertThat(client.submitPreparedPublication(null, "operation", payload)).containsEntry("originProductNo",
			"456");
		verify(rest).post("/v2/products", mapper.readTree(payload));
		var actual = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(payload);
		((com.fasterxml.jackson.databind.node.ObjectNode)actual.path("originProduct")).put("id", 456).put("statusType",
			"SALE");
		when(rest.get(any())).thenReturn(actual.toString());
		assertThat(client.verifyPreparedPublication("456", "SB-123", payload)).isTrue();
		assertThat(client.verifyPreparedPublication("456", "SB-OTHER", payload)).isFalse();
		((com.fasterxml.jackson.databind.node.ObjectNode)actual.path("originProduct")).put("salePrice", 12000);
		when(rest.get(any())).thenReturn(actual.toString());
		assertThat(client.verifyPreparedPublication("456", "SB-123", payload)).isFalse();
	}
}

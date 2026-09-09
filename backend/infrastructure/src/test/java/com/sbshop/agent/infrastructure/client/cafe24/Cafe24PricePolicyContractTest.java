package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class Cafe24PricePolicyContractTest {
	private final Cafe24RestClient rest = mock(Cafe24RestClient.class);
	private final Cafe24MarketClient client = new Cafe24MarketClient(new ObjectMapper(), rest, null, null, null, null);
	private static final String PRODUCT = "/admin/products/123?shop_no=1";
	private static final String SETTINGS = "/admin/products/setting?shop_no=1";

	@BeforeEach
	void account() {
		when(rest.accountReference()).thenReturn("account-A");
	}

	private void product(String calculation) {
		when(rest.get(PRODUCT)).thenReturn("{\"product\":{\"product_no\":123,\"shop_no\":1,\"price\":\"12300.00\","
			+ "\"selling\":\"T\",\"market_sync\":\"F\",\"tax_calculation\":\"" + calculation + "\"}}");
	}

	@Test
	void automaticTaxUsesPriceWithoutChangingStoreSettings() {
		product("A");
		var read = client.readSalePrice("123", null);
		assertThat(read.writable()).isTrue();
		assertThat(read.value()).isEqualByComparingTo("12300");
		verify(rest, never()).get(SETTINGS);
		client.writeSalePrice("123", null, new BigDecimal("12500"));
		verify(rest).put("/admin/products/123", Map.of("shop_no", 1, "request", Map.of("price", 12500)));
		verify(rest, never()).put(eq("/admin/products/setting"), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"S", "A", "P"})
	void manualTaxChecksDocumentedNonBasePriceSetting(String basis) {
		product("M");
		when(rest.get(SETTINGS))
			.thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"" + basis + "\"}}");
		assertThat(client.readSalePrice("123", null).writable()).isTrue();
		verify(rest).get(SETTINGS);
	}

	@Test
	void manualBasePriceCannotReceiveTaxInclusivePrice() {
		product("M");
		when(rest.get(SETTINGS)).thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"B\"}}");
		var read = client.readSalePrice("123", null);
		assertThat(read.writable()).isFalse();
		assertThat(read.reason()).contains("price_excluding_tax");
		assertThat(read.value()).isEqualByComparingTo("12300");
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "UNKNOWN"})
	void missingOrUnknownTaxModeDoesNotAssumeAutomatic(String mode) {
		product(mode);
		assertThat(client.readSalePrice("123", null).writable()).isFalse();
		verify(rest, never()).get(SETTINGS);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"product\":{\"shop_no\":2,\"calculate_price_based_on\":\"P\"}}",
		"{\"product\":{\"shop_no\":1.9,\"calculate_price_based_on\":\"P\"}}",
		"{\"product\":{\"shop_no\":1}}", "{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"UNKNOWN\"}}",
		"{\"error\":{\"message\":\"denied\"},\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"P\"}}"})
	void absentOrWrongStoreSettingsCannotAuthorizeWrite(String response) {
		product("M");
		when(rest.get(SETTINGS)).thenReturn(response);
		assertThat(client.readSalePrice("123", null).writable()).isFalse();
	}

	@Test
	void settingsPermissionFailureIsNotAnEmptyDefault() {
		product("M");
		when(rest.get(SETTINGS)).thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> client.readSalePrice("123", null))
			.isInstanceOf(MarketTransferFailure.class).hasMessageContaining("HTTP_403");
	}

	@Test
	void settings429PreservesRetryAfter() {
		product("M");
		var headers = new HttpHeaders();
		headers.set("Retry-After", "600");
		when(rest.get(SETTINGS)).thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
			"limited", headers, new byte[0], StandardCharsets.UTF_8));
		assertThatThrownBy(() -> client.readSalePrice("123", null))
			.isInstanceOfSatisfying(MarketTransferFailure.class, failure -> {
				assertThat(failure.rateLimited()).isTrue();
				assertThat(failure.getRetryAfter()).isAfter(Instant.now().plusSeconds(590));
			});
	}

	@Test
	void accountChangeDuringSettingsReadIsRejected() {
		product("M");
		when(rest.get(SETTINGS)).thenAnswer(invocation -> {
			when(rest.accountReference()).thenReturn("account-B");
			return "{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"P\"}}";
		});
		assertThatThrownBy(() -> client.readSalePrice("123", null)).isInstanceOf(MarketTransferFailure.class)
			.hasMessageContaining("계정이 변경");
	}

	@Test
	void marketPlusAndStoppedListingAllowPriceWithVerifiedTaxSettings() {
		when(rest.get(SETTINGS)).thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"S\"}}");
		for (String changes : new String[] {"\"selling\":\"F\",\"market_sync\":\"F\"",
			"\"selling\":\"T\",\"market_sync\":\"T\""}) {
			when(rest.get(PRODUCT)).thenReturn("{\"product\":{\"product_no\":123,\"shop_no\":1,\"price\":12300,"
				+ "\"tax_calculation\":\"M\"," + changes + "}}");
			assertThat(client.readSalePrice("123", null).writable()).isTrue();
		}
		verify(rest, times(2)).get(SETTINGS);
	}
}

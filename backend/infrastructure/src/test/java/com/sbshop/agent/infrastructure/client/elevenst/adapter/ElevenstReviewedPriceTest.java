package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ElevenstReviewedPriceTest {
	final ElevenstMarketRestClient rest = mock(ElevenstMarketRestClient.class);
	final ElevenstMarketClient adapter = new ElevenstMarketClient(rest);
	final String productPath = "/rest/prodmarketservice/prodmarket/123";
	final String writePath = "/rest/prodservices/product/price/123/10000";

	@BeforeEach
	void setup() {
		when(rest.accountReference()).thenReturn("account");
		product("103", "12000");
		when(rest.mutatePriceOnce(anyString(), eq("account"), any())).thenAnswer(call -> {
			call.getArgument(2, Runnable.class).run();
			return receipt("200", "123");
		});
	}

	String productXml(String state, String price) {
		return "<ns2:Product><prdNo>123</prdNo><sellerPrdCd>SB123</sellerPrdCd><selStatCd>" + state
			+ "</selStatCd><selPrc>" + price + "</selPrc><preSelPrc>9000</preSelPrc>"
			+ "<cuponcheck>Y</cuponcheck><cuponDscPrc>3000</cuponDscPrc></ns2:Product>";
	}

	void product(String state, String price) {
		when(rest.requestStrict("GET", productPath, null)).thenReturn(productXml(state, price));
	}

	String receipt(String code, String id) {
		return "<ClientMessage><resultCode>" + code + "</resultCode><productNo>" + id
			+ "</productNo><preSelPrc>12000</preSelPrc><message>가격 처리 결과</message></ClientMessage>";
	}

	void write(BigDecimal price, Runnable before) {
		adapter.writeSalePrice("123", null, "SB123", price, "account", before, true);
	}

	@ParameterizedTest
	@ValueSource(strings = {"103", "104", "105"})
	void readsCurrentBasePriceInSupportedStatusesWithoutUsingPreviousOrDiscountedPrice(String state) {
		product(state, "12000");
		var read = adapter.readSalePrice("123", null, "SB123");
		assertThat(read.value()).isEqualByComparingTo("12000");
		assertThat(read.writable()).isTrue();
		assertThat(read.accountReference()).isEqualTo("account");
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"101", "102", "106", "107", "108", "999"})
	void approvalEndedOrProhibitedStatusesCannotBeWritten(String state) {
		product(state, "12000");
		assertThat(adapter.readSalePrice("123", null, "SB123").writable()).isFalse();
		var before = mock(Runnable.class);
		assertThatThrownBy(() -> write(new BigDecimal("10000"), before))
			.isInstanceOf(UnsupportedOperationException.class);
		verifyNoInteractions(before);
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "0", "-1", "12000.00", "12,000", "1e4", "unknown"})
	void missingOrMalformedCurrentPriceNeverFallsBack(String price) {
		product("103", price);
		assertThatThrownBy(() -> adapter.readSalePrice("123", null, "SB123")).isInstanceOf(MarketTransferFailure.class);
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "10000.5", "2147483648"})
	void invalidTargetCannotBeRoundedOrSent(String price) {
		assertThatThrownBy(() -> write(new BigDecimal(price), () -> {})).isInstanceOf(MarketTransferFailure.class);
		verify(rest, never()).requestStrict(any(), any(), any());
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@Test
	void exactIdentityAndSingleFieldsAreMandatory() {
		for (String response : java.util.List.of(productXml("103", "12000").replace("SB123", "OTHER"),
			productXml("103", "12000").replace("<prdNo>123", "<prdNo>456"),
			productXml("103", "12000").replace("</selPrc>", "</selPrc><selPrc>1</selPrc>"),
			"<ClientMessage><resultCode>200</resultCode></ClientMessage>")) {
			when(rest.requestStrict("GET", productPath, null)).thenReturn(response);
			assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {}))
				.isInstanceOf(MarketTransferFailure.class);
		}
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@Test
	void lowersOnlyBasePriceAndKeepsCouponAndStatusApisUnused() {
		var before = mock(Runnable.class);
		write(new BigDecimal("10000.00"), before);
		var order = inOrder(rest, before);
		order.verify(rest).requestStrict("GET", productPath, null);
		order.verify(rest).mutatePriceOnce(eq(writePath), eq("account"), any());
		order.verify(before).run();
		verify(rest, never()).get(any());
		verify(rest, never()).put(any(), any());
		verify(rest, never()).post(any(), any());
		verify(rest, times(1)).requestStrict(any(), any(), any());
	}

	@Test
	void approvedIncreaseUsesSameDedicatedPriceEndpoint() {
		write(new BigDecimal("15000"), () -> {});
		verify(rest).mutatePriceOnce(eq("/rest/prodservices/product/price/123/15000"), eq("account"), any());
	}

	@Test
	void noChangeRequiresNoWriteIntent() {
		var before = mock(Runnable.class);
		write(new BigDecimal("12000"), before);
		verifyNoInteractions(before);
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@Test
	void guardExceptionRemainsIdenticalForDurableQueueOwnership() {
		var abort = new IllegalStateException("lost task ownership");
		assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {
			throw abort;
		})).isSameAs(abort);
	}

	@Test
	void accountChangeDuringFreshReadBlocksMutation() {
		when(rest.requestStrict("GET", productPath, null)).thenAnswer(call -> {
			when(rest.accountReference()).thenReturn("changed-account");
			return productXml("103", "12000");
		});
		assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {})).isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).mutatePriceOnce(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"500", "-1000"})
	void businessFailureIsNotHttpSuccess(String code) {
		doReturn(receipt(code, "123")).when(rest).mutatePriceOnce(any(), any(), any());
		assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {})).isInstanceOfSatisfying(
			MarketTransferFailure.class,
			failure -> assertThat(failure.getCode()).isEqualTo("ELEVENST_BUSINESS_" + code));
	}

	@Test
	void incorrectReceiptProductIdOrMissingPreviousPriceRemainsUnconfirmed() {
		for (String response : java.util.List.of(receipt("200", "456"),
			receipt("200", "123").replace("<productNo>123</productNo>", ""),
			receipt("200", "123").replace("<preSelPrc>12000</preSelPrc>", ""))) {
			doReturn(response).when(rest).mutatePriceOnce(any(), any(), any());
			assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {}))
				.isInstanceOf(MarketTransferFailure.class);
		}
	}

	@Test
	void responseLossIsTransportFailureAndDoesNotRetryMutation() {
		doThrow(new ResourceAccessException("lost response")).when(rest).mutatePriceOnce(any(), any(), any());
		assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {})).isInstanceOfSatisfying(
			MarketTransferFailure.class,
			failure -> assertThat(failure.getCode()).isEqualTo("TRANSPORT_ERROR"));
		verify(rest, times(1)).mutatePriceOnce(any(), any(), any());
	}

	@Test
	void rateLimitPreservesServerRetryAfter() {
		var headers = new HttpHeaders();
		headers.add("Retry-After", "600");
		doThrow(new RestClientResponseException("limited", 429, "", headers, new byte[0], Charset.forName("EUC-KR")))
			.when(rest).mutatePriceOnce(any(), any(), any());
		assertThatThrownBy(() -> write(new BigDecimal("10000"), () -> {}))
			.isInstanceOfSatisfying(MarketTransferFailure.class, failure -> {
				assertThat(failure.rateLimited()).isTrue();
				assertThat(failure.getRetryAfter()).isAfter(Instant.now().plusSeconds(590));
			});
		verify(rest, times(1)).mutatePriceOnce(any(), any(), any());
	}
}

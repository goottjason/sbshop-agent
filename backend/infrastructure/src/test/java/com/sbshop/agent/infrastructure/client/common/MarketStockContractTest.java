package com.sbshop.agent.infrastructure.client.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;

/** Verifies exact HTTP paths/bodies and refusal conditions without sending production traffic. */
class MarketStockContractTest {
	static final String P = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/123";
	static final String I = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/456/inventories";
	static final String Q = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/456/quantities/";

	CoupangMarketClient coupang(CoupangRestClient rest){
        when(rest.resolveVendorId()).thenReturn("A1");
        when(rest.get(P)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":123,\"vendorId\":\"A1\",\"statusName\":\"승인완료\",\"items\":[{\"vendorItemId\":456,\"externalVendorSku\":\"SB-123\"}]}}");
        when(rest.get(I)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"amountInStock\":10,\"onSale\":true}}");
        when(rest.put(any(),any())).thenReturn("{\"code\":\"SUCCESS\"}");
        return new CoupangMarketClient(null,new ObjectMapper(),rest,null,null,null,null,null,null);
    }

	@Test
	void coupangReadsInventoryAndWritesOnlyExactOptionQuantityAfterGuard() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		var guard = mock(Runnable.class);
		assertThat(client.readStockQuantity("123", "456", "SB-123").quantity()).isEqualTo(10);
		client.writeStockQuantity("123", "456", "SB-123", 300, client.inspectionAccountReference(), guard);
		var order = inOrder(rest, guard);
		order.verify(rest, times(2)).get(P);
		order.verify(rest).get(I);
		order.verify(guard).run();
		order.verify(rest).put(Q + "300", null);
		verify(rest, times(1)).put(any(), any());
	}

	@Test
	void coupangExplicitZeroIsValidButNeverCallsSellingStopOrResume() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		when(rest.get(I))
			.thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"amountInStock\":0,\"onSale\":true}}");
		assertThat(client.readStockQuantity("123", "456", "SB-123").quantity()).isZero();
		client.writeStockQuantity("123", "456", "SB-123", 0, client.inspectionAccountReference(), () -> {});
		verify(rest).put(Q + "0", null);
		verify(rest, times(1)).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "-1", "1.5", "2147483648", "\"300\"", "true", "{}"})
	void coupangMalformedQuantityNeverDefaultsToZero(String quantity) {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		when(rest.get(I)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"amountInStock\":"
			+ quantity + ",\"onSale\":true}}");
		assertThatThrownBy(() -> client.readStockQuantity("123", "456", "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void coupangWrongSbParentOptionVendorAndMissingOnSaleRefuseWrites() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "456", "OTHER", 300, client.inspectionAccountReference(), () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "789", "SB-123", 300, client.inspectionAccountReference(), () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		when(rest.get(I)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"amountInStock\":300}}");
		assertThatThrownBy(() -> client.readStockQuantity("123", "456", "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void coupangStoppedOrUnapprovedProductNeverRunsGuardOrWrites() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		var guard = mock(Runnable.class);
		when(rest.get(I)).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"amountInStock\":300,\"onSale\":false}}");
		assertThat(client.readStockQuantity("123", "456", "SB-123").writable()).isFalse();
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "456", "SB-123", 300, client.inspectionAccountReference(), guard))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(guard, never()).run();
		verify(rest, never()).put(any(), any());
	}

	@Test
	void guardAbortSurvivesAdapterUnwrappedAndPreventsPut() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		var aborted = new IllegalStateException("lease lost");
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "456", "SB-123", 300, client.inspectionAccountReference(), () -> {
				throw aborted;
			})).isSameAs(aborted);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void coupangAccountChangeInFinalGuardCannotSendThroughDifferentCredentials() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		String account = client.inspectionAccountReference();
		assertThatThrownBy(() -> client.writeStockQuantity("123", "456", "SB-123", 300, account,
			() -> when(rest.resolveVendorId()).thenReturn("B2"))).isInstanceOf(MarketTransferFailure.class);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void coupangBusinessFailureAnd429AreNotSuccessfulReceipts() {
		var rest = mock(CoupangRestClient.class);
		var client = coupang(rest);
		when(rest.put(any(), any())).thenReturn("{\"code\":\"ERROR\",\"message\":\"rejected\"}");
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "456", "SB-123", 300, client.inspectionAccountReference(), () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		var headers = new HttpHeaders();
		headers.set("Retry-After", "900");
		when(rest.get(I)).thenThrow(
			HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limit", headers, new byte[0], null));
		assertThatThrownBy(() -> client.readStockQuantity("123", "456", "SB-123"))
			.isInstanceOfSatisfying(MarketTransferFailure.class, e -> {
				assertThat(e.rateLimited()).isTrue();
				assertThat(e.getRetryAfter()).isAfter(Instant.now().plusSeconds(890));
			});
	}

	static final String CP = "/admin/products/123";
	static final String V = "P0000001000A";
	static final String CV = CP + "/variants/" + V;

	Cafe24MarketClient cafe(Cafe24RestClient rest) {
        when(rest.accountReference()).thenReturn("account-A");
        when(rest.get(CP+"?shop_no=1")).thenReturn("{\"product\":{\"shop_no\":1,\"product_no\":123,\"product_code\":\"P0000001\",\"custom_product_code\":\"SB-123\",\"market_sync\":\"F\",\"selling\":\"T\"}}");
        when(rest.get(CP+"/variants?shop_no=1")).thenReturn("{\"variants\":[{\"shop_no\":1,\"variant_code\":\""+V+"\"}]}");
        when(rest.get(CV+"?shop_no=1")).thenReturn("{\"variant\":{\"shop_no\":1,\"variant_code\":\""+V+"\",\"selling\":\"T\"}}");
        when(rest.get(CV+"/inventories?shop_no=1")).thenReturn("{\"inventory\":{\"shop_no\":1,\"variant_code\":\""+V+"\",\"quantity\":\"10\",\"use_inventory\":\"T\",\"display_soldout\":\"T\"}}");
        when(rest.put(any(),any())).thenReturn("{\"inventory\":{\"quantity\":300}}");
        return new Cafe24MarketClient(new ObjectMapper(),rest,null,null,null,null);
    }

	@Test
	void cafeResolvesOneExactVariantAndPutsQuantityAlonePreservingAllOtherSettings() {
		var rest = mock(Cafe24RestClient.class);
		var client = cafe(rest);
		var guard = mock(Runnable.class);
		var read = client.readStockQuantity("123", null, "SB-123");
		assertThat(read.quantity()).isEqualTo(10);
		assertThat(read.optionId()).isEqualTo(V);
		client.writeStockQuantity("123", V, "SB-123", 300, "account-A", guard);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).put(CV + "/inventories", Map.of("shop_no", 1, "request", Map.of("quantity", 300)));
		verify(rest, times(1)).put(any(), any());
	}

	@Test
	void cafeExplicitZeroUsesInventoryEndpointAndDoesNotStopOrResumeSelling() {
		var rest = mock(Cafe24RestClient.class);
		var client = cafe(rest);
		client.writeStockQuantity("123", V, "SB-123", 0, "account-A", () -> {});
		verify(rest).put(CV + "/inventories", Map.of("shop_no", 1, "request", Map.of("quantity", 0)));
		verify(rest, times(1)).put(any(), any());
	}

	@Test
	void cafeMarketPlusLinkedProductAndDifferentSbOrVariantAreBlocked() {
		var rest = mock(Cafe24RestClient.class);
		var client = cafe(rest);
		assertThatThrownBy(() -> client.writeStockQuantity("123", V, "OTHER", 300, "account-A", () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		assertThatThrownBy(() -> client.readStockQuantity("123", "P0000001000B", "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
		when(rest.get(CP + "?shop_no=1")).thenReturn(
			"{\"product\":{\"shop_no\":1,\"product_no\":123,\"product_code\":\"P0000001\",\"custom_product_code\":\"SB-123\",\"market_sync\":\"T\",\"selling\":\"T\"}}");
		assertThatThrownBy(() -> client.writeStockQuantity("123", V, "SB-123", 300, "account-A", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void cafeMultipleVariantsMustNotPickTheFirstAndAccountChangeNeverWrites() {
		var rest = mock(Cafe24RestClient.class);
		var client = cafe(rest);
		when(rest.get(CP + "/variants?shop_no=1")).thenReturn("{\"variants\":[{},{}]}");
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> client.writeStockQuantity("123", V, "SB-123", 300, "account-B", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "-1", "1.5", "2147483648", "true", "{}"})
	void cafeMalformedInventoryNeverDefaultsToZero(String quantity) {
		var rest = mock(Cafe24RestClient.class);
		var client = cafe(rest);
		when(rest.get(CV + "/inventories?shop_no=1")).thenReturn("{\"inventory\":{\"shop_no\":1,\"variant_code\":\"" + V
			+ "\",\"quantity\":" + quantity + ",\"use_inventory\":\"T\",\"display_soldout\":\"T\"}}");
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void cafeDisabledInventoryOrStoppedVariantDoesNotChangeSettingsToForceQuantity() {
		var rest = mock(Cafe24RestClient.class);
		var client = cafe(rest);
		when(rest.get(CV + "/inventories?shop_no=1")).thenReturn("{\"inventory\":{\"shop_no\":1,\"variant_code\":\"" + V
			+ "\",\"quantity\":0,\"use_inventory\":\"F\",\"display_soldout\":\"T\"}}");
		assertThat(client.readStockQuantity("123", null, "SB-123").writable()).isFalse();
		assertThatThrownBy(() -> client.writeStockQuantity("123", V, "SB-123", 0, "account-A", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).put(any(), any());
	}
}

package com.sbshop.agent.infrastructure.client.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;

class SmartstoreStockContractTest {
	static final String GET = "/v2/products/origin-products/123";
	static final String PATCH = "/v1/products/origin-products/multi-update";
	static final String PUT = "/v1/products/origin-products/123/option-stock";
	final ObjectMapper mapper = new ObjectMapper();
	SmartstoreRestClient rest;
	SmartstoreMarketClient client;

	@BeforeEach
	void setup() {
		rest = mock(SmartstoreRestClient.class);
		when(rest.accountReference()).thenReturn("account-A");
		when(rest.patch(any(), any())).thenReturn("{}");
		when(rest.put(any(), any())).thenReturn("{}");
		client = new SmartstoreMarketClient(null, null, null, null, rest, mapper);
		origin("SALE", 10, null);
	}

	void origin(String status, Object quantity, Map<String, Object> options) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("sellerCodeInfo", Map.of("sellerManagementCode", "SB-123"));
		if (options != null)
			detail.put("optionInfo", options);
		Map<String, Object> product = new LinkedHashMap<>();
		product.put("statusType", status);
		product.put("stockQuantity", quantity);
		product.put("detailAttribute", detail);
		when(rest.get(GET)).thenReturn(mapper.valueToTree(Map.of("originProduct", product)).toString());
	}

	Map<String, Object> combination(int quantity, boolean usable, int price) {
		return Map.of("optionCombinations",
			List.of(Map.of("id", 456, "stockQuantity", quantity, "usable", usable, "price", price)));
	}

	Map<String, Object> standard(int quantity, boolean managed) {
		return Map.of("optionStandards", List.of(Map.of("id", 789, "stockQuantity", quantity, "usable", true)),
			"useStockManagement", managed);
	}

	@Test
	void plainOriginUsesOnlyStockPatchAndDocumentedGetDoesNotNeedUndocumentedOriginId() {
		var proof = client.readStockQuantity("123", null, "SB-123");
		assertThat(proof.quantity()).isEqualTo(10);
		assertThat(proof.optionId()).isEqualTo("ORIGIN:123");
		assertThat(proof.writable()).isTrue();
		var guard = mock(Runnable.class);
		client.writeStockQuantity("123", proof.optionId(), "SB-123", 300, "account-A", guard);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).patch(PATCH,
			Map.of("multiProductUpdateRequestVos",
				List.of(Map.of("originProductNo", 123L, "multiUpdateTypes", List.of("STOCK"), "stockQuantity", 300))));
		verify(rest, never()).put(any(), any());
	}

	@Test
	void temporaryStockoutAndExplicitZeroRemainValidWithoutStatusResumePayload() {
		origin("OUTOFSTOCK", 0, null);
		assertThat(client.readStockQuantity("123", null, "SB-123").writable()).isTrue();
		client.writeStockQuantity("123", "ORIGIN:123", "SB-123", 0, "account-A", () -> {});
		verify(rest).patch(PATCH, Map.of("multiProductUpdateRequestVos",
			List.of(Map.of("originProductNo", 123L, "multiUpdateTypes", List.of("STOCK"), "stockQuantity", 0))));
		verify(rest, never()).put(any(), any());
	}

	@Test
	void combinationPutPreservesCurrentOptionPriceAndUsableInsteadOfDefaultingThem() {
		origin("SALE", 10, combination(10, true, 1200));
		var proof = client.readStockQuantity("123", null, "SB-123");
		assertThat(proof.optionId()).isEqualTo("COMBINATION:456");
		// Another editor's current option surcharge must survive the quantity-only operation.
		origin("SALE", 10, combination(10, true, 2300));
		client.writeStockQuantity("123", proof.optionId(), "SB-123", 300, "account-A", () -> {});
		verify(rest).put(PUT, Map.of("optionInfo", Map.of("optionCombinations",
			List.of(Map.of("id", 456L, "stockQuantity", 300, "price", 2300, "usable", true)))));
		verify(rest, never()).patch(any(), any());
	}

	@Test
	void standardPutPreservesEnabledInventoryManagementAndExplicitOption() {
		origin("SALE", 10, standard(10, true));
		assertThat(client.readStockQuantity("123", null, "SB-123").optionId()).isEqualTo("STANDARD:789");
		client.writeStockQuantity("123", "STANDARD:789", "SB-123", 300, "account-A", () -> {});
		verify(rest).put(PUT, Map.of("optionInfo", Map.of("optionStandards",
			List.of(Map.of("id", 789L, "stockQuantity", 300, "usable", true)), "useStockManagement", true)));
		verify(rest, never()).patch(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"WAIT", "UNADMISSION", "REJECTION", "SUSPENSION", "CLOSE", "PROHIBITION", "DELETE",
		"UNKNOWN"})
	void nonSellableStatusesNeverInvokeGuardOrResume(String status) {
		origin(status, 10, null);
		var guard = mock(Runnable.class);
		assertThat(client.readStockQuantity("123", null, "SB-123").writable()).isFalse();
		assertThatThrownBy(() -> client.writeStockQuantity("123", "ORIGIN:123", "SB-123", 300, "account-A", guard))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(guard, never()).run();
		verify(rest, never()).patch(any(), any());
		verify(rest, never()).put(any(), any());
	}

	@Test
	void wrongSbOptionalIdMismatchAndAccountRotationCannotWrite() throws Exception {
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "OTHER"))
			.isInstanceOf(MarketTransferFailure.class);
		var root = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(rest.get(GET));
		((com.fasterxml.jackson.databind.node.ObjectNode)root.path("originProduct")).put("id", 999);
		when(rest.get(GET)).thenReturn(root.toString());
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
		origin("SALE", 10, null);
		assertThatThrownBy(() -> client.writeStockQuantity("123", "ORIGIN:123", "SB-123", 300, "account-A",
			() -> when(rest.accountReference()).thenReturn("account-B"))).isInstanceOf(MarketTransferFailure.class);
		verify(rest, never()).patch(any(), any());
	}

	@Test
	void changingOptionKindOrIdDoesNotRetargetReviewedQuantity() {
		origin("SALE", 10, combination(10, true, 0));
		assertThatThrownBy(() -> client.writeStockQuantity("123", "ORIGIN:123", "SB-123", 300, "account-A", () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "COMBINATION:999", "SB-123", 300, "account-A", () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		origin("SALE", 10, standard(10, true));
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "COMBINATION:789", "SB-123", 300, "account-A", () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void multipleOptionsAndStandardInventoryDisabledAreExplicitlyBlocked() {
		origin("SALE", 20, Map.of("optionCombinations", List.of(Map.of("id", 456), Map.of("id", 789))));
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(UnsupportedOperationException.class);
		origin("SALE", 9999, standard(9999, false));
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void absentOptionPriceOrUsableCannotBeSilentlyResetByPut() {
		origin("SALE", 10,
			Map.of("optionCombinations", List.of(Map.of("id", 456, "stockQuantity", 10, "usable", true))));
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
		origin("SALE", 10, Map.of("optionCombinations", List.of(Map.of("id", 456, "stockQuantity", 10, "price", 0))));
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void disabledOptionAndFulfillmentSkuAreNotReactivated() {
		origin("SALE", 10, combination(10, false, 0));
		assertThat(client.readStockQuantity("123", null, "SB-123").writable()).isFalse();
		assertThatThrownBy(
			() -> client.writeStockQuantity("123", "COMBINATION:456", "SB-123", 300, "account-A", () -> {}))
			.isInstanceOf(UnsupportedOperationException.class);
		origin("SALE", 10, Map.of("optionCombinations",
			List.of(Map.of("id", 456, "stockQuantity", 10, "price", 0, "usable", true, "skuYn", true))));
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "-1", "1.5", "100000000", "\"300\"", "true", "{}"})
	void invalidQuantityIsNeverInventedAsZero(String value) throws Exception {
		origin("SALE", mapper.readTree(value), null);
		assertThatThrownBy(() -> client.readStockQuantity("123", null, "SB-123"))
			.isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void intentAbortIsUnwrappedAndBothTransportsRemainUntouched() {
		var lost = new IllegalStateException("lease lost");
		assertThatThrownBy(() -> client.writeStockQuantity("123", "ORIGIN:123", "SB-123", 300, "account-A", () -> {
			throw lost;
		})).isSameAs(lost);
		verify(rest, never()).patch(any(), any());
		verify(rest, never()).put(any(), any());
	}

	@Test void explicitBusinessRejectionAndHttp429RetainFailureEvidence() {
        when(rest.patch(any(),any())).thenReturn("{\"code\":\"FORBIDDEN\",\"message\":\"수정 권한 없음\"}");
        assertThatThrownBy(()->client.writeStockQuantity("123","ORIGIN:123","SB-123",300,"account-A",()->{}))
            .isInstanceOfSatisfying(MarketTransferFailure.class,e->{assertThat(e.getCode()).isEqualTo("HTTP_403");assertThat(e.getMessage()).contains("수정 권한 없음");});
        var headers=new HttpHeaders();headers.set("Retry-After","900");
        when(rest.get(GET)).thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,"limit",headers,new byte[0],null));
        assertThatThrownBy(()->client.readStockQuantity("123",null,"SB-123")).isInstanceOfSatisfying(MarketTransferFailure.class,e->{assertThat(e.rateLimited()).isTrue();assertThat(e.getRetryAfter()).isAfter(Instant.now().plusSeconds(890));});
    }
}

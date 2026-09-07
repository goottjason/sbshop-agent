package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sbshop.agent.infrastructure.client.cafe24.Cafe24VariantFixture.*;

import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class Cafe24MarketClientSoldOutTest {
	private final Cafe24VariantFixture f = new Cafe24VariantFixture();

	@Test
	void systemProductMustMatchBeforeAnyStockWrite() {
		var product = mock(com.sbshop.agent.core.domain.product.Product.class);
		when(product.getSbCode()).thenReturn("DIFFERENT_SB");
		assertThatThrownBy(() -> f.client.syncPriceAndStock(ID, new HashMap<>(), null, 300, false, product))
			.hasMessageContaining("SB코드");
		verify(f.rest, never()).put(any(), any());
	}

	@Test
	void changedProductIdentityCannotConfirmStock() {
		when(f.rest.get(PRODUCT + "?shop_no=1"))
			.thenReturn(product("T", "F", "A", "F", 23800),
				product("T", "F", "A", "F", 23800).replace("200630WA013", "DIFFERENT_SB"));
		assertThatThrownBy(() -> f.client.syncPriceAndStock(ID, new HashMap<>(), null, 500, false))
			.hasMessageContaining("SB코드가 변경");
	}

	@Test
    void noOptionInventoryIsWrittenToItsResourceAndReadBack() {
        when(f.rest.get(PRODUCT + "?shop_no=1")).thenReturn(product("T", "F", "A", "F", 23800));
        when(f.rest.get(INVENTORY + "?shop_no=1")).thenReturn(inventory(500,"T","T"), inventory(300,"T","T"));
        var raw = new HashMap<String, Object>(Map.of("memo", "keep"));
        var result = f.client.syncPriceAndStock(ID, raw, null, 300, false);
        verify(f.rest).put(INVENTORY, Map.of("shop_no",1,"request",Map.of("quantity",300)));
        verify(f.rest, never()).put(eq(PRODUCT), any());
        assertThat(result.toString()).contains("quantity=300", "CAFE24_NATIVE_ONLY", "memo=keep");
        assertThat(raw).isEqualTo(Map.of("memo", "keep"));
    }

	@Test
    void sourceStockoutStopsSellingAndPreservesQuantity() {
        when(f.rest.get(PRODUCT + "?shop_no=1"))
            .thenReturn(product("T","F","A","F",23800), product("F","F","A","F",23800));
        var result = f.client.syncPriceAndStock(ID, new HashMap<>(), null, 1, true);
        verify(f.rest).put(PRODUCT, Map.of("shop_no",1,"request",Map.of("selling","F")));
        verify(f.rest, never()).put(eq(INVENTORY), any());
        assertThat(result.toString()).contains("quantity=500", "selling=F");
        assertThat(((Map<?,?>)result.get("_sbshop_verified_fields")).get("fields")).isEqualTo(List.of("selling"));
    }

	@Test
    void priceAndQuantityNeedSeparateMatchingReads() {
        when(f.rest.get(PRODUCT + "?shop_no=1"))
            .thenReturn(product("T","F","A","F",23800), product("T","F","A","F",23900));
        when(f.rest.get(INVENTORY + "?shop_no=1")).thenReturn(inventory(500,"T","T"), inventory(300,"T","T"));
        var result = f.client.syncPriceAndStock(ID, new HashMap<>(), 23900, 300, false);
        verify(f.rest).put(PRODUCT, Map.of("shop_no",1,"request",Map.of("price",23900)));
        verify(f.rest).put(INVENTORY, Map.of("shop_no",1,"request",Map.of("quantity",300)));
        assertThat(result.toString()).contains("23900.00", "quantity=300");
    }

	@Test
	void matchingValuesDoNotWrite() {
		f.client.syncPriceAndStock(ID, new HashMap<>(), 23800, 500, false);
		verify(f.rest, never()).put(any(), any());
	}

	@Test
	void quantityMismatchDoesNotSendPriceOrChangeCache() {
		var raw = new HashMap<String, Object>(Map.of("price", "OLD"));
		when(f.rest.put(eq(INVENTORY), any())).thenReturn(inventory(300, "T", "T"));
		assertThatThrownBy(() -> f.client.syncPriceAndStock(ID, raw, 23900, 300, false)).hasMessageContaining("재조회");
		verify(f.rest, never()).put(eq(PRODUCT), any());
		assertThat(raw).isEqualTo(Map.of("price", "OLD"));
	}

	@Test
    void priceMismatchAfterStockWriteRemainsFailure() {
        when(f.rest.get(INVENTORY + "?shop_no=1")).thenReturn(inventory(500,"T","T"), inventory(300,"T","T"));
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),23900,300,false)).hasMessageContaining("일부 필드");
        verify(f.rest).put(eq(INVENTORY),any()); verify(f.rest).put(eq(PRODUCT),any());
    }

	@Test
    void stoppedProductIsNotAutomaticallyResumed() {
        when(f.rest.get(PRODUCT + "?shop_no=1")).thenReturn(product("F","F","A","F",23800));
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),null,300,false))
            .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("자동 판매 재개");
        verify(f.rest,never()).put(any(),any());
    }

	@ParameterizedTest
    @ValueSource(strings = {"T", "", "UNKNOWN"})
    void marketPlusOrMissingLinkScopePreventsAnyWrite(String market) {
        when(f.rest.get(PRODUCT + "?shop_no=1")).thenReturn(product("T",market,"A","F",23800));
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),23900,300,false))
            .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("마켓플러스");
        verify(f.rest,never()).put(any(),any());
    }

	@Test
	void inventorySettingsAreNotEnabledAsSideEffect() {
		for (String response : List.of(inventory(500, "F", "T"), inventory(500, "T", "F"))) {
			when(f.rest.get(INVENTORY + "?shop_no=1")).thenReturn(response);
			assertThatThrownBy(() -> f.client.syncPriceAndStock(ID, new HashMap<>(), null, 300, false))
				.hasMessageContaining("임의로 켜지");
		}
		verify(f.rest, never()).put(any(), any());
	}

	@Test
    void manualTaxBasisBIsCheckedBeforeAnyStockWrite() {
        when(f.rest.get(PRODUCT + "?shop_no=1")).thenReturn(product("T","F","M","F",23800));
        when(f.rest.get("/admin/products/setting?shop_no=1"))
            .thenReturn("{\"product\":{\"shop_no\":1,\"calculate_price_based_on\":\"B\"}}");
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),23900,300,false)).hasMessageContaining("price_excluding_tax");
        verify(f.rest,never()).put(any(),any());
    }

	@ParameterizedTest
    @ValueSource(strings = {"{}", "{\"inventory\":{\"shop_no\":2}}", "{\"inventory\":{\"shop_no\":1,\"variant_code\":\"OTHER\"}}", "{\"error\":{}}"})
    void malformedOrWrongInventoryCannotAuthorizeWrite(String response) {
        when(f.rest.get(INVENTORY + "?shop_no=1")).thenReturn(response);
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),null,300,false)).isInstanceOf(MarketTransferFailure.class);
        verify(f.rest,never()).put(any(),any());
    }

	@Test
	void inventory429PreservesRetryDelay() {
		var headers = new HttpHeaders();
		headers.set("Retry-After", "600");
		when(f.rest.get(INVENTORY + "?shop_no=1")).thenThrow(HttpClientErrorException
			.create(HttpStatus.TOO_MANY_REQUESTS, "limited", headers, new byte[0], StandardCharsets.UTF_8));
		assertThatThrownBy(() -> f.client.syncPriceAndStock(ID, new HashMap<>(), null, 300, false))
			.isInstanceOfSatisfying(MarketTransferFailure.class, e -> {
				assertThat(e.rateLimited()).isTrue();
				assertThat(e.getRetryAfter()).isAfter(Instant.now().plusSeconds(590));
			});
		verify(f.rest, never()).put(any(), any());
	}

	@Test
    void successfulHttpWithBusinessErrorIsNotConfirmed() {
        when(f.rest.put(any(),any())).thenReturn("{\"error\":{\"code\":422}}");
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),null,300,false)).hasMessageContaining("업무 오류");
    }

	@Test
    void wrongProductOrVariantCannotBeWritten() {
        when(f.rest.get(PRODUCT + "?shop_no=1"))
            .thenReturn(product("T","F","A","F",23800).replace("7034","7035"));
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),null,300,false)).hasMessageContaining("상품번호");
        when(f.rest.get(PRODUCT + "?shop_no=1")).thenReturn(product("T","F","A","F",23800));
        when(f.rest.get(PRODUCT + "/variants?shop_no=1"))
            .thenReturn("{\"variants\":[{\"shop_no\":1,\"variant_code\":\"P0000ABC000A\"}]}");
        assertThatThrownBy(() -> f.client.syncPriceAndStock(ID,new HashMap<>(),null,300,false)).hasMessageContaining("품목 코드");
        verify(f.rest,never()).put(any(),any());
    }
}

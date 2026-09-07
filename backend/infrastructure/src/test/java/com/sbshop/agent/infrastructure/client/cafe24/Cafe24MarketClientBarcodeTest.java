package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.sbshop.agent.infrastructure.client.cafe24.Cafe24VariantFixture.*;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Cafe24MarketClientBarcodeTest {
	private final Cafe24VariantFixture f = new Cafe24VariantFixture();

	private Product product(String barcode) {
		Product p = mock(Product.class);
		when(p.getProductSpec()).thenReturn(ProductSpec.builder().barcode(barcode).build());
		when(p.getSbCode()).thenReturn("200630WA013");
		return p;
	}

	@Test
    void confirmedGtinKeepsLeadingZeroAndShopOutsideRequest() {
        when(f.rest.get(VARIANT + "?shop_no=1")).thenReturn(variant("OLD"), variant("00012345678904"));
        var raw = new HashMap<String, Object>();
        assertThat(f.client.syncBarcode(product("00012345678904"), ID, raw)).isTrue();
        verify(f.rest).put(VARIANT, Map.of("shop_no", 1, "request", Map.of("gtin", "00012345678904")));
        verify(f.rest, times(2)).get(VARIANT + "?shop_no=1");
        assertThat(raw.toString()).contains("00012345678904", "CAFE24_NATIVE_ONLY");
    }

	@Test
	void existingGtinIsReadAndDoesNotResend() {
		var raw = new HashMap<String, Object>();
		raw.put("gtin", "STALE_CACHE");
		assertThat(f.client.syncBarcode(product("00012345678905"), ID, raw)).isFalse();
		verify(f.rest, never()).put(any(), any());
		assertThat(raw.get("variants").toString()).contains("00012345678905");
	}

	@Test
	void successfulPutWithDifferentReadbackDoesNotChangeCache() {
		var raw = new HashMap<String, Object>(Map.of("variants", List.of(Map.of("gtin", "old"))));
		when(f.rest.put(any(), any())).thenReturn(variant("00012345678904"));
		assertThatThrownBy(() -> f.client.syncBarcode(product("00012345678904"), ID, raw))
			.hasMessageContaining("재조회");
		assertThat(raw).isEqualTo(Map.of("variants", List.of(Map.of("gtin", "old"))));
		verify(f.rest, times(1)).put(any(), any());
	}

	@Test
    void lostWriteResponseCanBeRetriedByReadWithoutSecondPut() {
        when(f.rest.put(any(), any())).thenAnswer(call -> {
            when(f.rest.get(VARIANT + "?shop_no=1")).thenReturn(variant("00012345678904"));
            throw new IllegalStateException("timeout");
        });
        var raw = new HashMap<String, Object>();
        assertThatThrownBy(() -> f.client.syncBarcode(product("00012345678904"), ID, raw)).hasMessageContaining("timeout");
        assertThat(raw).isEmpty();
        assertThat(f.client.syncBarcode(product("00012345678904"), ID, raw)).isFalse();
        verify(f.rest, times(1)).put(any(), any());
    }

	@Test
    void multipleVariantsNeverSelectFirst() {
        when(f.rest.get(PRODUCT + "/variants?shop_no=1"))
            .thenReturn("{\"variants\":[{\"shop_no\":1,\"variant_code\":\"P0000KKO000A\"},{\"shop_no\":1,\"variant_code\":\"P0000KKO000B\"}]}");
        assertThatThrownBy(() -> f.client.syncBarcode(product("00012345678904"), ID, new HashMap<>()))
            .hasMessageContaining("첫 품목");
        verify(f.rest, never()).put(any(), any());
    }

	@ParameterizedTest
    @ValueSource(strings = {"", "F", "UNKNOWN"})
    void noOptionOrUnknownSupportRemainsExplicitlyUnverified(String hasOption) {
        when(f.rest.get(PRODUCT + "?shop_no=1")).thenReturn(Cafe24VariantFixture.product("T", "F", "A", hasOption, 23800));
        assertThatThrownBy(() -> f.client.syncBarcode(product("00012345678904"), ID, new HashMap<>()))
            .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("검증");
        verify(f.rest, never()).put(any(), any());
    }

	@Test
	void differentSbCodeBlocksWrite() {
		Product p = product("00012345678904");
		when(p.getSbCode()).thenReturn("OTHER");
		assertThatThrownBy(() -> f.client.syncBarcode(p, ID, new HashMap<>())).hasMessageContaining("SB코드");
		verify(f.rest, never()).put(any(), any());
	}

	@Test
    void changedAccountDuringReadbackCannotConfirmGtin() {
        when(f.rest.get(VARIANT + "?shop_no=1")).thenReturn(variant("OLD")).thenAnswer(call -> {
            when(f.rest.accountReference()).thenReturn("account-B"); return variant("00012345678904");
        });
        var raw = new HashMap<String, Object>();
        assertThatThrownBy(() -> f.client.syncBarcode(product("00012345678904"), ID, raw)).hasMessageContaining("계정");
        assertThat(raw).isEmpty();
    }

	@Test
	void noBarcodeDoesNotClearExistingGtin() {
		assertThat(f.client.syncBarcode(product(null), ID, new HashMap<>())).isFalse();
		verify(f.rest, never()).put(any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"123456789012345", "ABC", "123 456"})
	void invalidGtinFailsBeforeWrite(String gtin) {
		assertThatThrownBy(() -> f.client.syncBarcode(product(gtin), ID, new HashMap<>()))
			.isInstanceOf(IllegalArgumentException.class);
		verify(f.rest, never()).put(any(), any());
	}
}

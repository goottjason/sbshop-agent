package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketRegistrationExtractMarketCodeTest {

	@Test
	@DisplayName("[D-052] 쿠팡: vendorItemId 우선, 없으면 sellerProductId")
	void coupang_prefersVendorItemId_thenSellerProductId() {
		assertThat(reg(MarketType.COUPANG, "{\"vendorItemId\":\"VI1\",\"sellerProductId\":\"SP1\"}")
			.extractMarketCode()).isEqualTo("VI1");
		assertThat(reg(MarketType.COUPANG, "{\"sellerProductId\":\"SP1\"}")
			.extractMarketCode()).isEqualTo("SP1");
	}

	@Test
	@DisplayName("[D-052] 스마트스토어: originProductNo")
	void smartstore_readsOriginProductNo() {
		assertThat(reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP123\"}")
			.extractMarketCode()).isEqualTo("OP123");
	}

	@Test
	@DisplayName("[D-052] 11번가: elevenstId")
	void elevenst_readsElevenstId() {
		assertThat(reg(MarketType.ELEVEN_STREET, "{\"elevenstId\":\"E999\"}")
			.extractMarketCode()).isEqualTo("E999");
	}

	@Test
	@DisplayName("[D-052] 카페24: product_no 우선, 없으면 product_code")
	void cafe24_prefersProductNo_thenProductCode() {
		assertThat(reg(MarketType.CAFE24, "{\"product_no\":\"P10\",\"product_code\":\"C10\"}")
			.extractMarketCode()).isEqualTo("P10");
		assertThat(reg(MarketType.CAFE24, "{\"product_code\":\"C10\"}")
			.extractMarketCode()).isEqualTo("C10");
	}

	@Test
	@DisplayName("[D-052] ESM+(지마켓/옥션): goodsNo")
	void esmplus_readsGoodsNo() {
		assertThat(reg(MarketType.GMARKET, "{\"goodsNo\":\"G777\"}")
			.extractMarketCode()).isEqualTo("G777");
		assertThat(reg(MarketType.AUCTION, "{\"goodsNo\":\"A777\"}")
			.extractMarketCode()).isEqualTo("A777");
	}

	@Test
	@DisplayName("[D-052] 스토어 코드를 쿠팡 키(vendorItemId)로 읽던 회귀 재현: 이제 null이 아니어야 한다")
	void smartstore_wasNullUnderVendorItemIdLookup_nowResolves() {
		MarketRegistration store = reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP123\"}");
		assertThat(store.extractVendorItemId()).isNull();
		assertThat(store.extractMarketCode()).isEqualTo("OP123");
	}

	@Test
	@DisplayName("[D-052] 식별자 JSON이 비면 null")
	void emptyIdentifiers_returnsNull() {
		assertThat(reg(MarketType.SMART_STORE, null).extractMarketCode()).isNull();
		assertThat(reg(MarketType.SMART_STORE, "").extractMarketCode()).isNull();
	}

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(1L)
			.sbProductId(10L)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.build();
	}
}

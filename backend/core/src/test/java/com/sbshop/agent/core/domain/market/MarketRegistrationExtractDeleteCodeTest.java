package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-PROD-27/28 완전삭제: 삭제용 식별자 추출. 쿠팡만 sellerProductId(삭제 seller-products 경로),
 * 나머지는 extractMarketCode와 동일.
 */
class MarketRegistrationExtractDeleteCodeTest {

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(1L)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.build();
	}

	@Test
	@DisplayName("쿠팡 삭제코드는 sellerProductId (extractMarketCode의 vendorItemId 우선과 다름)")
	void coupang_usesSellerProductId() {
		MarketRegistration r = reg(MarketType.COUPANG,
			"{\"vendorItemId\":\"V123\",\"sellerProductId\":\"S999\"}");
		assertThat(r.extractDeleteCode()).isEqualTo("S999");
		// 대비: extractMarketCode는 vendorItemId 우선 → 삭제엔 부적합
		assertThat(r.extractMarketCode()).isEqualTo("V123");
	}

	@Test
	@DisplayName("쿠팡에 sellerProductId 없으면 null(삭제 불가 → best-effort 실패 수집)")
	void coupang_missingSellerProductId_null() {
		MarketRegistration r = reg(MarketType.COUPANG, "{\"vendorItemId\":\"V123\"}");
		assertThat(r.extractDeleteCode()).isNull();
	}

	@Test
	@DisplayName("Cafe24는 extractMarketCode와 동일(product_no)")
	void cafe24_sameAsMarketCode() {
		MarketRegistration r = reg(MarketType.CAFE24, "{\"product_no\":\"21159\"}");
		assertThat(r.extractDeleteCode()).isEqualTo("21159");
		assertThat(r.extractDeleteCode()).isEqualTo(r.extractMarketCode());
	}

	@Test
	@DisplayName("스마트스토어는 extractMarketCode와 동일(originProductNo)")
	void smartStore_sameAsMarketCode() {
		MarketRegistration r = reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP77\"}");
		assertThat(r.extractDeleteCode()).isEqualTo("OP77");
	}
}

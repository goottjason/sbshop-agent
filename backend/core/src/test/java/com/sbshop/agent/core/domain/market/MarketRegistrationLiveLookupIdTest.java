package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketRegistrationLiveLookupIdTest {

	@Test
	@DisplayName("[D-206] 쿠팡: 실시간 조회는 seller-products 경로 → sellerProductId (vendorItemId 아님)")
	void coupang_usesSellerProductId() {
		MarketRegistration reg = reg(MarketType.COUPANG,
			"{\"externalVendorSku\":\"X\",\"sellerProductId\":\"14813281569\","
				+ "\"vendorItemId\":\"89379432362\",\"productId\":\"70535073\"}");

		assertThat(reg.extractLiveLookupId()).isEqualTo("14813281569");
	}

	@Test
	@DisplayName("[D-206] 스마트스토어: origin-products 경로 → originProductNo")
	void smartstore_usesOriginProductNo() {
		MarketRegistration reg = reg(MarketType.SMART_STORE,
			"{\"originProductNo\":\"6321468668\",\"sellerManagementCode\":\"S\","
				+ "\"channelProductNo\":\"6351684748\"}");

		assertThat(reg.extractLiveLookupId()).isEqualTo("6321468668");
	}

	@Test
	@DisplayName("[D-206] 11번가: productinfo 경로 → prdNo, 없으면 elevenstId 폴백")
	void elevenst_usesPrdNoThenElevenstId() {
		assertThat(reg(MarketType.ELEVEN_STREET,
			"{\"sellerPrdCd\":\"220227IHB052\",\"prdNo\":\"4193852605\"}")
			.extractLiveLookupId()).isEqualTo("4193852605");
		assertThat(reg(MarketType.ELEVEN_STREET, "{\"elevenstId\":\"4193852605\"}")
			.extractLiveLookupId()).isEqualTo("4193852605");
	}

	@Test
	@DisplayName("[D-206] 카페24: /admin/products 경로 → product_no (product_code 아님)")
	void cafe24_usesProductNo() {
		MarketRegistration reg = reg(MarketType.CAFE24,
			"{\"product_no\":\"17624\",\"product_code\":\"P000BABW\"}");

		assertThat(reg.extractLiveLookupId()).isEqualTo("17624");
	}

	@Test
	@DisplayName("[D-206] 식별자가 없으면 null — 로컬 PK로 폴백하지 않는다")
	void missingIdentifier_returnsNull() {
		assertThat(reg(MarketType.SMART_STORE, "{\"channelProductNo\":\"6351684748\"}")
			.extractLiveLookupId()).isNull();
		assertThat(reg(MarketType.COUPANG, "{}").extractLiveLookupId()).isNull();
		assertThat(reg(MarketType.CAFE24, null).extractLiveLookupId()).isNull();
	}

	@Test
	@DisplayName("[D-206] 실시간 조회 미지원 마켓(G마켓/옥션)은 조회 키가 없다")
	void unsupportedMarkets_haveNoKeys() {
		assertThat(MarketRegistration.liveLookupKeys(MarketType.GMARKET)).isEmpty();
		assertThat(MarketRegistration.liveLookupKeys(MarketType.AUCTION)).isEmpty();
		assertThat(MarketRegistration.liveLookupKeys(MarketType.UNKNOWN)).isEmpty();
	}

	private MarketRegistration reg(MarketType type, String identifiers) {
		return MarketRegistration.builder()
			.productId(1592L)
			.marketType(type)
			.marketIdentifiers(identifiers)
			.build();
	}
}

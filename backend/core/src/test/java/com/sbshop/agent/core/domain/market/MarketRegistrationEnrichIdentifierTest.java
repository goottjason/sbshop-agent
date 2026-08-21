package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketRegistrationEnrichIdentifierTest {

	@Test
	@DisplayName("[D-046] sellerProductId만 있던 JSON에 vendorItemId를 병합하고 기존 키를 보존한다")
	void enrichIdentifier_mergesVendorItemId_preservingSellerProductId() {
		MarketRegistration reg = coupangReg("{\"sellerProductId\":\"SP123\"}");

		reg.enrichIdentifier("vendorItemId", "VI456");

		assertThat(reg.extractVendorItemId()).isEqualTo("VI456");
		assertThat(reg.getMarketIdentifiers()).contains("SP123");
	}

	@Test
	@DisplayName("[D-046] 식별자 JSON이 비어 있어도 vendorItemId를 새로 기록한다")
	void enrichIdentifier_onEmptyIdentifiers_recordsVendorItemId() {
		MarketRegistration reg = coupangReg(null);

		reg.enrichIdentifier("vendorItemId", "VI789");

		assertThat(reg.extractVendorItemId()).isEqualTo("VI789");
	}

	@Test
	@DisplayName("[D-046] 값이 비어 있으면 식별자를 변경하지 않는다")
	void enrichIdentifier_withBlankValue_isNoOp() {
		MarketRegistration reg = coupangReg("{\"sellerProductId\":\"SP123\"}");

		reg.enrichIdentifier("vendorItemId", "");
		reg.enrichIdentifier("vendorItemId", null);

		assertThat(reg.extractVendorItemId()).isNull();
		assertThat(reg.getMarketIdentifiers()).contains("SP123");
	}

	private MarketRegistration coupangReg(String identifiersJson) {
		return MarketRegistration.builder()
			.productId(1L)
			.sbProductId(10L)
			.marketType(MarketType.COUPANG)
			.marketIdentifiers(identifiersJson)
			.build();
	}
}

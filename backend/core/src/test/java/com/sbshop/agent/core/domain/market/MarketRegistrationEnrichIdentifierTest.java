package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-046 (사이클 10 Batch B): 발행 시 marketIdentifiers에 sellerProductId만 저장돼
 * vendorItemId를 채우는 write-path가 코드에 존재하지 않던 "구조적 공백"을 메운다.
 * enrichIdentifier()가 기존 식별자 JSON을 보존하며 vendorItemId를 병합함을 고정한다.
 */
class MarketRegistrationEnrichIdentifierTest {

	private MarketRegistration coupangReg(String identifiersJson) {
		return MarketRegistration.builder()
			.productId(1L)
			.sbProductId(10L)
			.marketType(MarketType.COUPANG)
			.marketIdentifiers(identifiersJson)
			.build();
	}

	@Test
	@DisplayName("[D-046] sellerProductId만 있던 JSON에 vendorItemId를 병합하고 기존 키를 보존한다")
	void enrichIdentifier_mergesVendorItemId_preservingSellerProductId() {
		MarketRegistration reg = coupangReg("{\"sellerProductId\":\"SP123\"}");

		reg.enrichIdentifier("vendorItemId", "VI456");

		// vendorItemId가 실제로 기록되어 extractVendorItemId()로 읽힌다 (D-046 매칭 소스)
		assertThat(reg.extractVendorItemId()).isEqualTo("VI456");
		// 기존 sellerProductId도 보존
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
}

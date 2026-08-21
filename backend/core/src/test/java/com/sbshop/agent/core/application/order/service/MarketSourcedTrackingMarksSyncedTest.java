package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.vo.ShippingData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketSourcedTrackingMarksSyncedTest {
	@Test
	@DisplayName("[D-129] 마켓이 실송장을 주면 마켓 보유(true)로 판정한다")
	void realMarketTrackingMeansMarketOwnsIt() {
		assertThat(ShippingData.marketOwnsTracking("424410280092")).isTrue();
		assertThat(ShippingData.marketOwnsTracking("6079990333504")).isTrue();
	}

	@Test
	@DisplayName("[D-129] 자리표시자·빈값은 마킹하지 않는다(null = 기존 값 유지)")
	void placeholderOrBlankDoesNotMark() {
		assertThat(ShippingData.marketOwnsTracking("00000000")).isNull();
		assertThat(ShippingData.marketOwnsTracking("")).isNull();
		assertThat(ShippingData.marketOwnsTracking(null)).isNull();
	}

	@Test
	@DisplayName("[D-129] 판정 기준은 송장 실값 판정(isMeaningfulTracking)과 일치한다")
	void agreesWithMeaningfulTracking() {
		for (String candidate : new String[] {"424410280092", "00000000", "", "0-0-0", " 123 ", null}) {
			boolean adopted = ShippingData.isMeaningfulTracking(candidate);
			assertThat(ShippingData.marketOwnsTracking(candidate) != null)
				.as("candidate=%s", candidate)
				.isEqualTo(adopted);
		}
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;

class TrackingMismatchPolicyTest {

	private Shipment shipment(String mail, String market) {
		Shipment s = Shipment.builder().orderId(1L).marketShipmentNo("SH-1").build();
		s.applyTracking(mail, null, Boolean.FALSE);
		s.applyMarketTracking(market);
		return s;
	}

	@Test
	@DisplayName("쿠팡은 불일치를 자동 재전송으로 푼다 — 유일하게 송장 수정 API 가 있다")
	void coupangAutoResends() {
		assertThat(TrackingMismatchPolicy.of(MarketType.COUPANG, shipment("MAIL-1", "MARKET-1")))
			.isEqualTo(TrackingMismatchPolicy.AUTO_RESEND);
	}

	@Test
	@DisplayName("나머지 마켓은 사람이 처리해야 한다 — 발송 후 수정 API 가 없다")
	void otherMarketsNeedManualFix() {
		assertThat(TrackingMismatchPolicy.of(MarketType.SMART_STORE, shipment("MAIL-1", "MARKET-1")))
			.isEqualTo(TrackingMismatchPolicy.MANUAL_FIX);
		assertThat(TrackingMismatchPolicy.of(MarketType.ELEVEN_STREET, shipment("MAIL-1", "MARKET-1")))
			.isEqualTo(TrackingMismatchPolicy.MANUAL_FIX);
		assertThat(TrackingMismatchPolicy.of(MarketType.GMARKET, shipment("MAIL-1", "MARKET-1")))
			.isEqualTo(TrackingMismatchPolicy.MANUAL_FIX);
		assertThat(TrackingMismatchPolicy.of(MarketType.AUCTION, shipment("MAIL-1", "MARKET-1")))
			.isEqualTo(TrackingMismatchPolicy.MANUAL_FIX);
	}

	@Test
	@DisplayName("두 송장이 같으면 아무 조치도 필요 없다")
	void matchedNeedsNothing() {
		assertThat(TrackingMismatchPolicy.of(MarketType.COUPANG, shipment("SAME", "SAME")))
			.isEqualTo(TrackingMismatchPolicy.NONE);
	}

	@Test
	@DisplayName("우리 송장이 없으면 대조할 것이 없다 — 마켓 값만 있는 상태는 정상이다")
	void noOwnTrackingNeedsNothing() {
		assertThat(TrackingMismatchPolicy.of(MarketType.COUPANG, shipment(null, "MARKET-1")))
			.isEqualTo(TrackingMismatchPolicy.NONE);
	}

	@Test
	@DisplayName("마켓 송장이 아직 없으면 불일치가 아니다 — 전송 전일 뿐이다")
	void noMarketTrackingIsNotMismatch() {
		assertThat(TrackingMismatchPolicy.of(MarketType.COUPANG, shipment("MAIL-1", null)))
			.isEqualTo(TrackingMismatchPolicy.NONE);
	}
}

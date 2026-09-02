package com.sbshop.agent.core.application.order.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

class OrderProbeResultClaimTest {

	@Test
	@DisplayName("프로브는 배송 단계와 클레임을 함께 실어 나른다 — 두 축이 한 응답에 온다")
	void carriesBothAxes() {
		ClaimData claim = ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.REQUESTED).claimRawCode("E00").build();

		OrderProbeResult r = OrderProbeResult.found(ShippingStatus.SHIPPED, claim, "1234567890");

		assertThat(r.shippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(r.claim().getClaimRawCode()).isEqualTo("E00");
		assertThat(r.marketTrackingNo()).isEqualTo("1234567890");
	}

	@Test
	@DisplayName("옛 팩토리는 클레임 없이 배송 단계만 싣는다 — 기존 호출부를 깨지 않는다")
	void legacyFactoryStillWorks() {
		OrderProbeResult r = OrderProbeResult.found(ShippingStatus.DELIVERED);

		assertThat(r.shippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(r.claim()).isNull();
		assertThat(r.marketTrackingNo()).isNull();
	}

	@Test
	@DisplayName("확인하지 못한 응답에는 클레임도 송장도 없다 — 짐작하지 않는다")
	void unknownCarriesNothing() {
		assertThat(OrderProbeResult.unknown("timeout").claim()).isNull();
		assertThat(OrderProbeResult.notFound("없음").marketTrackingNo()).isNull();
	}
}

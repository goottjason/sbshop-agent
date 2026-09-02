package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

class Cafe24ClaimMappingTest {

	@Test
	@DisplayName("배송 코드는 배송 단계로만 간다 — 클레임은 없음이다")
	void deliveryCodesMapToStageOnly() {
		assertThat(Cafe24LineItemMapper.mapStatus("N00")).isEqualTo(ShippingStatus.NEW);
		assertThat(Cafe24LineItemMapper.mapStatus("N10")).isEqualTo(ShippingStatus.NEW);
		assertThat(Cafe24LineItemMapper.mapStatus("N20")).isEqualTo(ShippingStatus.PREPARING);
		assertThat(Cafe24LineItemMapper.mapStatus("N30")).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(Cafe24LineItemMapper.mapStatus("N40")).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(Cafe24LineItemMapper.mapClaim("N30").getClaimType()).isEqualTo(ClaimType.NONE);
	}

	@Test
	@DisplayName("N50 은 구매확정이다 — 배송완료로 뭉개지 않는다")
	void n50IsConfirmed() {
		assertThat(Cafe24LineItemMapper.mapStatus("N50")).isEqualTo(ShippingStatus.CONFIRMED);
	}

	@Test
	@DisplayName("N01·N03 은 교환으로 새로 나가는 상품이다 — 지금까지 UNKNOWN 으로 버렸다")
	void exchangeReplacementItemsAreMapped() {
		assertThat(Cafe24LineItemMapper.mapStatus("N01")).isEqualTo(ShippingStatus.NEW);
		assertThat(Cafe24LineItemMapper.mapStatus("N03")).isEqualTo(ShippingStatus.NEW);
		assertThat(Cafe24LineItemMapper.mapClaim("N01").getClaimType()).isEqualTo(ClaimType.EXCHANGE);
	}

	@Test
	@DisplayName("교환 14단계가 요청·처리중·완료·거부로 갈린다 — 접두어로 뭉개지 않는다")
	void exchangeStagesAreDistinguished() {
		assertThat(Cafe24LineItemMapper.mapClaim("E00").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(Cafe24LineItemMapper.mapClaim("E10").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(Cafe24LineItemMapper.mapClaim("E11").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(Cafe24LineItemMapper.mapClaim("E30").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(Cafe24LineItemMapper.mapClaim("E40").getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

	@Test
	@DisplayName("E40 교환완료는 배송 단계를 덮지 않는다 — 재발송돼 배송중일 수 있다(D-268)")
	void exchangeDoneDoesNotMaskDeliveryStage() {
		ClaimData claim = Cafe24LineItemMapper.mapClaim("E40");

		assertThat(claim.getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(claim.getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(claim.isRefundTerminal()).isFalse();
		assertThat(Cafe24LineItemMapper.mapStatus("E40")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("반품도 단계별로 갈린다 — 수거 전인지 환불까지 끝났는지 구분된다")
	void returnStagesAreDistinguished() {
		assertThat(Cafe24LineItemMapper.mapClaim("R00").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(Cafe24LineItemMapper.mapClaim("R11").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(Cafe24LineItemMapper.mapClaim("R20").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(Cafe24LineItemMapper.mapClaim("R40").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(Cafe24LineItemMapper.mapClaim("R43").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(Cafe24LineItemMapper.mapClaim("R40").isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("취소도 단계별로 갈린다 — 입금전취소도 완료로 본다")
	void cancelStagesAreDistinguished() {
		assertThat(Cafe24LineItemMapper.mapClaim("C00").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(Cafe24LineItemMapper.mapClaim("C11").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(Cafe24LineItemMapper.mapClaim("C34").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(Cafe24LineItemMapper.mapClaim("C40").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(Cafe24LineItemMapper.mapClaim("C47").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(Cafe24LineItemMapper.mapClaim("C47").isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("마켓 원본 코드를 그대로 남긴다")
	void keepsRawCode() {
		assertThat(Cafe24LineItemMapper.mapClaim("E40").getClaimRawCode()).isEqualTo("E40");
		assertThat(Cafe24LineItemMapper.mapClaim("N30").getClaimRawCode()).isNull();
	}

	@Test
	@DisplayName("모르는 코드는 클레임으로 만들지 않는다 — 짐작하지 않는다")
	void unknownCodeIsNotAClaim() {
		assertThat(Cafe24LineItemMapper.mapClaim("Z99").getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(Cafe24LineItemMapper.mapStatus("Z99")).isEqualTo(ShippingStatus.UNKNOWN);
	}
}

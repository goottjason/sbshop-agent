package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

class CoupangClaimMappingTest {
	private final CoupangStatusMapper mapper = new CoupangStatusMapper();

	@Test
	@DisplayName("배송 6상태는 배송 단계로만 간다 — 클레임 문자열이 오면 UNKNOWN이다(D-270)")
	void deliveryStatusesNoLongerMapToClaimShippingStatus() {
		assertThat(mapper.mapStatus(java.util.Map.of("status", "CANCELED"))).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapStatus(java.util.Map.of("status", "CANCEL_RECEIPT"))).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapStatus(java.util.Map.of("status", "CANCEL_DONE"))).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapStatus(java.util.Map.of("status", "RETURN_RECEIPT"))).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapStatus(java.util.Map.of("status", "RETURN_DONE"))).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapStatus(java.util.Map.of("status", "EXCHANGE_RECEIPT"))).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapStatus(java.util.Map.of("status", "EXCHANGE_DONE"))).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("반품 5단계가 요청·처리중·완료로 갈린다")
	void returnStagesAreDistinguished() {
		assertThat(mapper.mapClaim("RETURN", "RETURNS_UNCHECKED").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim("RETURN", "RELEASE_STOP_UNCHECKED").getClaimStage())
			.isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("RETURN", "VENDOR_WAREHOUSE_CONFIRM").getClaimStage())
			.isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("RETURN", "REQUEST_COUPANG_CHECK").getClaimStage())
			.isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("RETURN", "RETURNS_COMPLETED").getClaimStage()).isEqualTo(ClaimStage.DONE);

		ClaimData completed = mapper.mapClaim("RETURN", "RETURNS_COMPLETED");
		assertThat(completed.getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(completed.isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("취소도 반품과 같은 단계 규칙을 쓴다 — 타입만 CANCEL이다")
	void cancelUsesSameStageRulesAsReturn() {
		assertThat(mapper.mapClaim("CANCEL", "RETURNS_UNCHECKED").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim("CANCEL", "RETURNS_COMPLETED").getClaimStage()).isEqualTo(ClaimStage.DONE);

		ClaimData completed = mapper.mapClaim("CANCEL", "RETURNS_COMPLETED");
		assertThat(completed.getClaimType()).isEqualTo(ClaimType.CANCEL);
		assertThat(completed.isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("모르는 receiptType은 클레임으로 만들지 않는다 — 짐작하지 않는다")
	void unknownReceiptTypeIsNotAClaim() {
		assertThat(mapper.mapClaim("SOMETHING_NEW", "RETURNS_COMPLETED").getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(mapper.mapClaim(null, "RETURNS_COMPLETED").getClaimType()).isEqualTo(ClaimType.NONE);
	}

	@Test
	@DisplayName("마켓 원본 코드(receiptStatus)를 그대로 남긴다")
	void keepsRawReceiptStatus() {
		assertThat(mapper.mapClaim("RETURN", "RETURNS_COMPLETED").getClaimRawCode()).isEqualTo("RETURNS_COMPLETED");
	}

	@Test
	@DisplayName("교환 5단계가 요청·처리중·완료·거부로 갈린다")
	void exchangeStagesAreDistinguished() {
		assertThat(mapper.mapExchangeClaim("RECEIPT").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapExchangeClaim("PROGRESS").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapExchangeClaim("SUCCESS").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapExchangeClaim("REJECT").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(mapper.mapExchangeClaim("CANCEL").getClaimStage()).isEqualTo(ClaimStage.REJECTED);

		ClaimData success = mapper.mapExchangeClaim("SUCCESS");
		assertThat(success.getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(success.isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("모르는 exchangeStatus는 클레임으로 만들지 않는다")
	void unknownExchangeStatusIsNotAClaim() {
		assertThat(mapper.mapExchangeClaim("SOMETHING_NEW").getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(mapper.mapExchangeClaim(null).getClaimType()).isEqualTo(ClaimType.NONE);
	}
}

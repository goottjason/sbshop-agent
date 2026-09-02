package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

class SmartStoreClaimMappingTest {
	private final SmartStoreStatusMapper mapper = new SmartStoreStatusMapper();

	@Test
	@DisplayName("취소 4단계가 요청·처리중·완료·철회로 갈린다")
	void cancelStagesAreDistinguished() {
		assertThat(mapper.mapClaim(null, "CANCEL", "CANCEL_REQUEST").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim(null, "CANCEL", "CANCELING").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim(null, "CANCEL", "CANCEL_DONE").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaim(null, "CANCEL", "CANCEL_REJECT").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
	}

	@Test
	@DisplayName("반품은 수거처리중·수거완료가 모두 처리중이고 반품완료에서 끝난다")
	void returnStagesAreDistinguished() {
		assertThat(mapper.mapClaim(null, "RETURN", "RETURN_REQUEST").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim(null, "RETURN", "COLLECTING").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim(null, "RETURN", "COLLECT_DONE").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim(null, "RETURN", "RETURN_DONE").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaim(null, "RETURN", "RETURN_REJECT").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(mapper.mapClaim(null, "RETURN", "RETURN_DONE").isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("교환은 재배송중까지 처리중이고 교환완료에서 끝난다 — 대금이 유지돼 환불종결이 아니다")
	void exchangeStagesAreDistinguished() {
		assertThat(mapper.mapClaim(null, "EXCHANGE", "EXCHANGE_REQUEST").getClaimStage())
			.isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim(null, "EXCHANGE", "EXCHANGE_REDELIVERING").getClaimStage())
			.isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim(null, "EXCHANGE", "EXCHANGE_DONE").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaim(null, "EXCHANGE", "EXCHANGE_REJECT").getClaimStage())
			.isEqualTo(ClaimStage.REJECTED);

		ClaimData done = mapper.mapClaim(null, "EXCHANGE", "EXCHANGE_DONE");
		assertThat(done.getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(done.isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("직권취소(ADMIN_CANCEL)도 취소 타입으로 잡는다")
	void adminCancelIsCancelType() {
		assertThat(mapper.mapClaim(null, "ADMIN_CANCEL", "CANCEL_DONE").getClaimType()).isEqualTo(ClaimType.CANCEL);
	}

	@Test
	@DisplayName("구매확정보류·구매확정요청은 클레임이 아니다 — 배송 절차의 일부일 뿐이다")
	void purchaseDecisionHoldbackIsNotAClaim() {
		assertThat(mapper.mapClaim(null, "PURCHASE_DECISION_HOLDBACK", "PURCHASE_DECISION_HOLDBACK")
			.getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(mapper.mapClaim(null, null, "PURCHASE_DECISION_REQUEST").getClaimType())
			.isEqualTo(ClaimType.NONE);
	}

	@Test
	@DisplayName("미입금취소(CANCELED_BY_NOPAYMENT)는 claimType이 안 와도 클레임 쪽에서 취소완료로 잡는다")
	void canceledByNoPaymentIsCaughtOnClaimSide() {
		ClaimData claim = mapper.mapClaim("CANCELED_BY_NOPAYMENT", null, null);

		assertThat(claim.getClaimType()).isEqualTo(ClaimType.CANCEL);
		assertThat(claim.getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(claim.isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("클레임 코드가 전혀 없으면 클레임 없음이다")
	void noClaimCodesMeansNoClaim() {
		assertThat(mapper.mapClaim("DELIVERING", null, null).getClaimType()).isEqualTo(ClaimType.NONE);
	}

	@Test
	@DisplayName("모르는 claimType은 클레임으로 만들지 않는다 — 짐작하지 않는다")
	void unknownClaimTypeIsNotAClaim() {
		assertThat(mapper.mapClaim(null, "SOMETHING_NEW", "SOMETHING_NEW").getClaimType()).isEqualTo(ClaimType.NONE);
	}

	@Test
	@DisplayName("마켓 원본 코드를 남긴다")
	void keepsRawCode() {
		assertThat(mapper.mapClaim(null, "RETURN", "RETURN_DONE").getClaimRawCode()).isEqualTo("RETURN_DONE");
		assertThat(mapper.mapClaim("CANCELED_BY_NOPAYMENT", null, null).getClaimRawCode())
			.isEqualTo("CANCELED_BY_NOPAYMENT");
	}
}

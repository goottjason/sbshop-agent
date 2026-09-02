package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

class ElevenstClaimMappingTest {
	private final ElevenstStatusMapper mapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("구매확정은 CONFIRMED다 — 배송완료로 뭉개지 않는다")
	void purchaseConfirmedIsConfirmedNotDelivered() {
		assertThat(mapper.mapProductOrderStatus("구매확정")).isEqualTo(ShippingStatus.CONFIRMED);
		assertThat(mapper.mapProductOrderStatus("배송완료")).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("클레임 이름이 상품주문 상태 함수에 들어오면 배송 단계는 UNKNOWN이다 — mapClaim이 읽는다")
	void claimNamesAreUnknownInShippingStage() {
		assertThat(mapper.mapProductOrderStatus("반품완료")).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapProductOrderStatus("교환신청")).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapProductOrderStatus("취소완료")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("반품 코드가 신청·처리중·완료·거부·철회로 갈린다 — 코드 단위, 접두어 뭉개기 없음")
	void returnCodesAreDistinguished() {
		assertThat(mapper.mapClaim("105").getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(mapper.mapClaim("105").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim("103").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("104").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("109").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("106").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaim("107").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(mapper.mapClaim("108").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
	}

	@Test
	@DisplayName("반품완료는 환불종결이다 — D-098이 기대는 신호")
	void returnDoneIsRefundTerminal() {
		assertThat(mapper.mapClaim("106").isRefundTerminal()).isTrue();
		assertThat(mapper.mapClaim("105").isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("교환 코드가 6단계로 갈린다 — 신청·승인·보류·발송완료·거부·철회")
	void exchangeCodesAreDistinguished() {
		assertThat(mapper.mapClaim("201").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaim("212").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("214").getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
		assertThat(mapper.mapClaim("221").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaim("232").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(mapper.mapClaim("233").getClaimStage()).isEqualTo(ClaimStage.REJECTED);
		assertThat(mapper.mapClaim("201").getClaimType()).isEqualTo(ClaimType.EXCHANGE);
	}

	@Test
	@DisplayName("교환발송완료는 환불이 아니다 — 결제는 유지된다")
	void exchangeDoneIsNotRefundTerminal() {
		assertThat(mapper.mapClaim("221").isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("재배송접수는 교환이 끝나고도 이어지는 경유지다 — 배송 단계를 덮지 않는다")
	void redeliveryAcceptedIsExchangeInProgress() {
		ClaimData claim = mapper.mapClaim("301");

		assertThat(claim.getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(claim.getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
	}

	@Test
	@DisplayName("마켓 원본 코드를 그대로 남긴다")
	void keepsRawCode() {
		assertThat(mapper.mapClaim("221").getClaimRawCode()).isEqualTo("221");
	}

	@Test
	@DisplayName("모르는 코드나 빈 값은 클레임으로 만들지 않는다 — 짐작하지 않는다")
	void unknownOrBlankCodeIsNotAClaim() {
		assertThat(mapper.mapClaim("999").getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(mapper.mapClaim(null).getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(mapper.mapClaim("").getClaimType()).isEqualTo(ClaimType.NONE);
	}

	@Test
	@DisplayName("clmStat이 아직 실리지 않는 응답에서는 상태명을 정확히 대조해 같은 판정을 낸다 — 부분일치 아님")
	void nameBridgeMatchesExactLabelsOnly() {
		assertThat(mapper.mapClaimByStatusName("반품완료").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaimByStatusName("반품완료").getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(mapper.mapClaimByStatusName("교환발송완료").getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(mapper.mapClaimByStatusName("취소완료").getClaimType()).isEqualTo(ClaimType.CANCEL);
		assertThat(mapper.mapClaimByStatusName("취소신청").getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(mapper.mapClaimByStatusName("배송중").getClaimType()).isEqualTo(ClaimType.NONE);
	}
}

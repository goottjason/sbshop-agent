package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

class ClaimDataTest {

	@Test
	@DisplayName("기본값은 클레임 없음이다 — 대부분의 주문은 클레임이 없다")
	void defaultsToNone() {
		ClaimData data = ClaimData.builder().build();

		assertThat(data.getClaimType()).isEqualTo(ClaimType.NONE);
		assertThat(data.getClaimStage()).isEqualTo(ClaimStage.NONE);
		assertThat(data.isActive()).isFalse();
	}

	@Test
	@DisplayName("반품 완료는 환불 대상이다 — 정산액이 0 이 되어야 한다")
	void returnDoneIsRefundTerminal() {
		ClaimData data = ClaimData.builder()
			.claimType(ClaimType.RETURN).claimStage(ClaimStage.DONE).build();

		assertThat(data.isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("교환 완료는 환불 대상이 아니다 — 결제가 유지된다")
	void exchangeDoneIsNotRefundTerminal() {
		ClaimData data = ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.DONE).build();

		assertThat(data.isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("마켓 원본 코드를 그대로 보관한다 — 매핑이 틀렸을 때 되짚을 수 있어야 한다")
	void keepsRawCode() {
		ClaimData data = ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.DONE)
			.claimRawCode("E40").build();

		assertThat(data.getClaimRawCode()).isEqualTo("E40");
	}

	@Test
	@DisplayName("화면 라벨은 '반품 요청' 처럼 조합된다 — 클레임이 없으면 빈 값이다")
	void buildsLabel() {
		assertThat(ClaimData.builder()
			.claimType(ClaimType.RETURN).claimStage(ClaimStage.REQUESTED).build().getLabel())
			.isEqualTo("반품 요청");
		assertThat(ClaimData.builder().build().getLabel()).isNull();
	}

	@Test
	@DisplayName("거부·철회된 클레임은 살아 있지 않다 — 주문은 배송 단계로 돌아간다")
	void rejectedIsNotActive() {
		ClaimData data = ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.REJECTED).build();

		assertThat(data.isActive()).isFalse();
		assertThat(data.isRefundTerminal()).isFalse();
	}
}

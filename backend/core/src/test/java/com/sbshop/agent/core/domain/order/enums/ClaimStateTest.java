package com.sbshop.agent.core.domain.order.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaimStateTest {

	@Test
	@DisplayName("클레임이 없으면 배송 단계를 가리지 않는다")
	void noneDoesNotMask() {
		assertThat(ClaimType.NONE.isActive()).isFalse();
		assertThat(ClaimStage.NONE.isActive()).isFalse();
	}

	@Test
	@DisplayName("취소·반품이 끝나면 환불 대상이다 — 교환은 결제가 유지되므로 아니다")
	void refundTerminalOnlyForCancelAndReturn() {
		assertThat(ClaimType.CANCEL.isRefundTerminalAt(ClaimStage.DONE)).isTrue();
		assertThat(ClaimType.RETURN.isRefundTerminalAt(ClaimStage.DONE)).isTrue();
		assertThat(ClaimType.EXCHANGE.isRefundTerminalAt(ClaimStage.DONE)).isFalse();
	}

	@Test
	@DisplayName("아직 진행 중이거나 무산된 클레임은 환불 대상이 아니다")
	void inProgressOrRejectedIsNotRefund() {
		assertThat(ClaimType.RETURN.isRefundTerminalAt(ClaimStage.REQUESTED)).isFalse();
		assertThat(ClaimType.RETURN.isRefundTerminalAt(ClaimStage.IN_PROGRESS)).isFalse();
		assertThat(ClaimType.RETURN.isRefundTerminalAt(ClaimStage.REJECTED)).isFalse();
		assertThat(ClaimType.CANCEL.isRefundTerminalAt(ClaimStage.NONE)).isFalse();
	}

	@Test
	@DisplayName("무산된 클레임은 배송 단계로 돌아간다 — 거부·철회는 없던 일이 된다")
	void rejectedReleasesTheOrder() {
		assertThat(ClaimStage.REJECTED.isActive()).isFalse();
		assertThat(ClaimStage.REQUESTED.isActive()).isTrue();
		assertThat(ClaimStage.IN_PROGRESS.isActive()).isTrue();
		assertThat(ClaimStage.DONE.isActive()).isTrue();
	}

	@Test
	@DisplayName("화면 라벨은 타입과 단계를 조합해 만든다 — '반품 요청' 처럼 읽힌다")
	void labelCombinesTypeAndStage() {
		assertThat(ClaimType.RETURN.getLabel()).isEqualTo("반품");
		assertThat(ClaimStage.REQUESTED.getLabel()).isEqualTo("요청");
		assertThat(ClaimStage.DONE.getLabel()).isEqualTo("완료");
		assertThat(ClaimStage.REJECTED.getLabel()).isEqualTo("거부");
	}
}

package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;

class SyncErrorReasonRecordedTest {

	@Test
	@DisplayName("원인 예외의 메시지가 원문으로 남는다 — 분류만으로는 어느 필드가 문제인지 알 수 없다")
	void rootMessageIsStored() {
		MarketRegistration reg = MarketRegistration.builder().build();
		Exception e = new IllegalStateException(
			"originProduct.statusType: NotValidEnum, 유효하지 않은 값입니다");

		reg.recordSyncError(SyncErrorType.VALIDATION_FAILED, e.getMessage());

		assertThat(reg.getLastSyncErrorMessage()).contains("statusType");
		assertThat(reg.getLastSyncErrorAt()).isNotNull();
	}
}

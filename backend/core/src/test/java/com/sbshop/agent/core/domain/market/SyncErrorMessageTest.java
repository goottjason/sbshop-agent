package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실패 분류만으로는 무엇이 막혔는지 알 수 없다.
 * BLOCKED_BY_MARKET 이 "심사중이라 못 지운다"인지 "권한이 없다"인지 구분하려면 원문이 필요하다.
 */
class SyncErrorMessageTest {

	private MarketRegistration registration() {
		return MarketRegistration.builder().productId(1L).marketType(MarketType.COUPANG).build();
	}

	@Test
	@DisplayName("분류와 함께 원문 사유도 남긴다 — 사람이 읽고 조치할 수 있어야 한다")
	void keepsMessageWithType() {
		MarketRegistration reg = registration();

		reg.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET,
			"업체상품[10621199335]이 없거나 삭제가 불가능한 상태입니다.");

		assertThat(reg.getLastSyncError()).isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
		assertThat(reg.getLastSyncErrorMessage()).contains("삭제가 불가능한 상태");
	}

	@Test
	@DisplayName("긴 메시지는 잘라 담는다 — 컬럼을 넘겨 저장이 통째로 실패하면 안 된다")
	void truncatesLongMessage() {
		MarketRegistration reg = registration();

		reg.recordSyncError(SyncErrorType.TRANSIENT_ERROR, "x".repeat(2000));

		assertThat(reg.getLastSyncErrorMessage().length()).isLessThanOrEqualTo(500);
	}

	@Test
	@DisplayName("성공하면 사유도 지운다 — 해결된 건이 계속 실패로 보이면 안 된다")
	void clearsOnSuccess() {
		MarketRegistration reg = registration();
		reg.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET, "심사중");

		reg.markSynced();

		assertThat(reg.getLastSyncError()).isNull();
		assertThat(reg.getLastSyncErrorMessage()).isNull();
	}

	@Test
	@DisplayName("사유 없이도 기록할 수 있다 — 기존 호출부를 깨지 않는다")
	void allowsNullMessage() {
		MarketRegistration reg = registration();

        reg.recordSyncError(SyncErrorType.TRANSIENT_ERROR);

		assertThat(reg.getLastSyncError()).isEqualTo(SyncErrorType.TRANSIENT_ERROR);
	}
}

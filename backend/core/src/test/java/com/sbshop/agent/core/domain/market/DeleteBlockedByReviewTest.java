package com.sbshop.agent.core.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 쿠팡은 심사중·승인대기 상품을 삭제할 수 없다(사용자 실측 2026-09-01).
 * 마켓 화면에서 직접 지워도 "승인대기중/심사중인 상품은 삭제할 수 없습니다."가 뜬다.
 *
 * <p>이건 <b>일시 오류가 아니다</b> — 429(요청 한도)처럼 재시도로 풀리지 않고,
 * 심사가 끝나야 풀린다. 재시도 대상과 섞이면 매번 같은 건에서 실패가 반복된다.
 */
class DeleteBlockedByReviewTest {

	@Test
	@DisplayName("쿠팡 삭제 거부 문구를 '마켓이 막았다'로 분류한다 — 재시도로 풀리지 않는다")
	void coupangReviewBlocksDeletion() {
		Exception e = new IllegalStateException("409 CONFLICT: {\"code\":\"ERROR\","
			+ "\"message\":\"업체상품[10621199335]이 없거나 삭제가 불가능한 상태입니다."
			+ " 삭제는 '저장중' 상태에서만 가능합니다.\"}");

		assertThat(MarketFailureClassifier.classifyError(e))
			.isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
	}

	@Test
	@DisplayName("마켓 화면 문구도 같은 분류다 — 승인대기중/심사중")
	void coupangUiMessageBlocks() {
		Exception e = new IllegalStateException("승인대기중/심사중인 상품은 삭제할 수 없습니다.");

		assertThat(MarketFailureClassifier.classifyError(e))
			.isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
	}

	@Test
	@DisplayName("요청 한도(429)는 일시 오류다 — 재시도하면 풀린다")
	void rateLimitIsTransient() {
		Exception e = new IllegalStateException("429 Too Many Requests: {\"code\":\"GW.RATE_LIMIT\","
			+ "\"message\":\"요청이 많아 서비스를 일시적으로 사용할 수 없습니다.\"}");

		assertThat(MarketFailureClassifier.classifyError(e))
			.isEqualTo(SyncErrorType.TRANSIENT_ERROR);
	}

	@Test
	@DisplayName("삭제 거부는 '마켓에서 사라짐'이 아니다 — 상품은 여전히 마켓에 있다")
	void blockedIsNotDeleted() {
		Exception e = new IllegalStateException("업체상품[123]이 없거나 삭제가 불가능한 상태입니다.");

		assertThat(MarketFailureClassifier.indicatesDeleted(e)).isFalse();
	}
}

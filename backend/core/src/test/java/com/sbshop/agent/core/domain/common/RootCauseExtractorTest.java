package com.sbshop.agent.core.domain.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-SYNC-14: 3곳에 복붙돼 있던 root-cause 추출 로직을 공통 유틸로 통합.
 * 기존 3개 호출부(OrderSyncController preview/carriers, Cafe24OrderSyncService.failureReason)의
 * 동작을 그대로 보존하는지 특성화 검증.
 */
class RootCauseExtractorTest {

	@Test
	@DisplayName("단일 예외는 자신의 메시지를 반환한다")
	void returnsOwnMessageWhenNoCause() {
		assertThat(RootCauseExtractor.rootMessage(new RuntimeException("boom"))).isEqualTo("boom");
	}

	@Test
	@DisplayName("중첩 예외는 최심 원인 메시지를 반환한다")
	void returnsDeepestCauseMessage() {
		Throwable root = new IllegalStateException("real cause");
		Throwable mid = new RuntimeException("wrapper1", root);
		Throwable top = new RuntimeException("Cafe24 API 호출 실패", mid);
		assertThat(RootCauseExtractor.rootMessage(top)).isEqualTo("real cause");
	}

	@Test
	@DisplayName("null 예외는 null을 반환한다")
	void returnsNullForNullThrowable() {
		assertThat(RootCauseExtractor.rootMessage(null)).isNull();
	}

	@Test
	@DisplayName("최심 원인 메시지가 null이면 null을 반환한다(String.valueOf 시 \"null\")")
	void returnsNullWhenDeepestMessageNull() {
		Throwable root = new NullPointerException(); // getMessage() == null
		Throwable top = new RuntimeException("wrap", root);
		assertThat(RootCauseExtractor.rootMessage(top)).isNull();
	}

	@Test
	@DisplayName("자기 참조 순환에서도 무한루프 없이 반환한다")
	void handlesSelfReferentialCause() {
		RuntimeException e = new RuntimeException("self") {
			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};
		assertThat(RootCauseExtractor.rootMessage(e)).isEqualTo("self");
	}
}

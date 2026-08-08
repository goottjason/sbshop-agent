package com.sbshop.agent.worker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.service.MarketTrackingBackfillService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 마켓 보유 송장 백필 트리거의 안전장치.
 *
 * <p>이 트리거는 네 마켓 API를 과거 구간까지 호출한다 — 실수로 부르면 부담이 크고, 무인증이면
 * 외부에서 부를 수 있다. 토큰 가드와 기간 상한을 계약으로 고정한다.
 * (구간 분할·페이싱은 {@link MarketTrackingBackfillService}의 책임이다.)
 */
class MarketTrackingBackfillControllerTest {

	private MarketTrackingBackfillService backfillService;
	private InternalAccessGuard guard;
	private MarketTrackingBackfillController controller;

	@BeforeEach
	void setUp() {
		backfillService = mock(MarketTrackingBackfillService.class);
		guard = mock(InternalAccessGuard.class);
		controller = new MarketTrackingBackfillController(backfillService, guard);
	}

	@Test
	@DisplayName("토큰이 없으면 403 — 백필을 시작하지 않는다")
	void rejectsWithoutToken() {
		when(guard.isAllowed(null)).thenReturn(false);

		var response = controller.backfill(120, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(backfillService, never()).backfill(anyInt());
	}

	@Test
	@DisplayName("토큰이 있으면 지정한 기간으로 백필을 시작한다")
	void startsBackfill() {
		when(guard.isAllowed("t")).thenReturn(true);

		var response = controller.backfill(120, "t");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(backfillService).backfill(120);
	}

	@Test
	@DisplayName("기간이 상한(365일)을 넘거나 0 이하면 거부한다 — 실수로 마켓 API를 과호출하지 않게")
	void rejectsOutOfRangeDays() {
		when(guard.isAllowed("t")).thenReturn(true);

		assertThat(controller.backfill(0, "t").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(controller.backfill(366, "t").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(backfillService, never()).backfill(anyInt());
	}
}

package com.sbshop.agent.core.application.actionlog;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogSyncListenerTest {

	@Mock
	private ActionLogService actionLogService;
	@Mock
	private SyncStatusService syncStatusService;
	@InjectMocks
	private ActionLogSyncListener listener;

	@Test
	void success_recordsSuccess() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG));

		verify(actionLogService).record(
			ArgumentMatchers.eq("COUPANG_SYNC"),
			ArgumentMatchers.eq("COUPANG"),
			ArgumentMatchers.eq(ActionStatus.SUCCESS),
			ArgumentMatchers.anyString());
	}

	@Test
	void failure_recordsFailedWithErrorMessage() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG, false,
			"쿠팡 주문 조회 실패: 403"));

		verify(actionLogService).record(
			ArgumentMatchers.eq("COUPANG_SYNC"),
			ArgumentMatchers.eq("COUPANG"),
			ArgumentMatchers.eq(ActionStatus.FAILED),
			ArgumentMatchers.contains("403"));
	}

	@Test
	@DisplayName("성공 메시지에 처리 건수와 신규 건수가 남는다 — 0건 성공을 사후에 구분할 수 있어야 한다")
	void success_recordsCounts() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 12, 3));

		verify(actionLogService).record(
			ArgumentMatchers.eq("GMARKET_SYNC"),
			ArgumentMatchers.eq("GMARKET"),
			ArgumentMatchers.eq(ActionStatus.SUCCESS),
			ArgumentMatchers.contains("처리 12건, 신규 3건"));
	}

	@Test
	@DisplayName("신규 0건이 임계를 넘겨 지속되면 별도 경고 로그를 남긴다")
	void staleNewOrders_recordsWarning() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.GMARKET))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(12)));

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 0));

		verify(actionLogService).record(
			ArgumentMatchers.eq("GMARKET_SYNC_STALE"),
			ArgumentMatchers.eq("GMARKET"),
			ArgumentMatchers.eq(ActionStatus.WARNING),
			ArgumentMatchers.contains("신규 주문이"));
	}

	@Test
	@DisplayName("신규 0건이어도 임계 이내면 경고하지 않는다")
	void freshEnough_doesNotWarn() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.GMARKET))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(3)));

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 0));

		verify(actionLogService, never()).record(
			ArgumentMatchers.eq("GMARKET_SYNC_STALE"),
			ArgumentMatchers.anyString(),
			ArgumentMatchers.any(),
			ArgumentMatchers.anyString());
	}

	@Test
	@DisplayName("신규가 들어온 회차는 공백이 길었더라도 경고하지 않는다")
	void newOrdersArrived_doesNotWarn() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 2));

		verify(actionLogService, never()).record(
			ArgumentMatchers.eq("GMARKET_SYNC_STALE"),
			ArgumentMatchers.anyString(),
			ArgumentMatchers.any(),
			ArgumentMatchers.anyString());
	}
}

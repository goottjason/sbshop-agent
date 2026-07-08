package com.sbshop.agent.core.application.actionlog;

import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-042: SyncCompletedEvent 리스너가 성공/실패를 SUCCESS/FAILED로 올바르게 기록하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class ActionLogSyncListenerTest {

	@Mock
	private ActionLogService actionLogService;
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
}

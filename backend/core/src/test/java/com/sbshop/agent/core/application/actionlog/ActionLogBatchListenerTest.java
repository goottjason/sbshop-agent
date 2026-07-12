package com.sbshop.agent.core.application.actionlog;

import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogBatchListenerTest {

	@Mock ActionLogService actionLogService;

	@Test
	@DisplayName("배치 성공 이벤트를 SUCCESS 활동로그로 기록한다")
	void recordsSuccess() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(new BatchCompletedEvent(this, "B-1", "BATCH_CRAWL_UPDATE", true, "배치 완료"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_CRAWL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.SUCCESS), ArgumentMatchers.contains("B-1"));
	}

	@Test
	@DisplayName("배치 실패 이벤트를 FAILED 활동로그로 기록한다")
	void recordsFailed() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(new BatchCompletedEvent(this, "B-2", "BATCH_MANUAL_UPDATE", false, "배치 완료(실패 1건)"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_MANUAL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.FAILED), ArgumentMatchers.contains("B-2"));
	}
}

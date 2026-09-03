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

	@Mock
	ActionLogService actionLogService;

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

	@Test
	@DisplayName("D-269-E: 실패 0 부분실패 0 이면 SUCCESS 로 남긴다")
	void recordsSuccess_zeroFailZeroPartial() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(
			new BatchCompletedEvent(this, "B-3", "BATCH_CRAWL_UPDATE", 0, 0, "배치 완료"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_CRAWL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.SUCCESS), ArgumentMatchers.contains("B-3"));
	}

	@Test
	@DisplayName("D-269-E: 부분실패만 있으면 활동로그에 WARNING 으로 남긴다 — 통째로 실패한 게 아니다")
	void recordsWarning_onlyPartialFailure() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(
			new BatchCompletedEvent(this, "B-4", "BATCH_CRAWL_UPDATE", 0, 1, "배치 완료(부분실패 1건)"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_CRAWL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.WARNING), ArgumentMatchers.contains("B-4"));
	}

	@Test
	@DisplayName("D-269-E: 완전 실패면 부분실패 유무와 무관하게 FAILED 로 남긴다")
	void recordsFailed_fullFailureOnly() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(
			new BatchCompletedEvent(this, "B-5", "BATCH_MANUAL_UPDATE", 1, 0, "배치 완료(실패 1건)"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_MANUAL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.FAILED), ArgumentMatchers.contains("B-5"));
	}

	@Test
	@DisplayName("D-269-E: 완전 실패와 부분실패가 섞여도 FAILED 로 남긴다")
	void recordsFailed_fullAndPartialMixed() {
		var listener = new ActionLogBatchListener(actionLogService);
		listener.onBatchCompleted(
			new BatchCompletedEvent(this, "B-6", "BATCH_MANUAL_UPDATE", 1, 1, "배치 완료(실패 1건, 부분실패 1건)"));
		verify(actionLogService).record(
			ArgumentMatchers.eq("BATCH_MANUAL_UPDATE"), ArgumentMatchers.isNull(),
			ArgumentMatchers.eq(ActionStatus.FAILED), ArgumentMatchers.contains("B-6"));
	}
}

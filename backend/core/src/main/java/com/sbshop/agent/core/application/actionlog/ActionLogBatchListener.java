package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 배치 완료 이벤트를 활동 로그로 자동 기록한다 (SP-F).
 * BatchCompletedEvent는 발행되나 수신처가 없어 배치가 영구 STARTED로만 남던 문제를 해소.
 * core에 두어 api(수동 배치)·worker(스케줄 배치) 양 JVM에서 DB 기록된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionLogBatchListener {

	private final ActionLogService actionLogService;

	@EventListener
	public void onBatchCompleted(BatchCompletedEvent event) {
		ActionStatus status = event.isSuccess() ? ActionStatus.SUCCESS : ActionStatus.FAILED;
		actionLogService.record(event.getActionType(), null, status,
			event.getMessage() + " (batchId=" + event.getBatchId() + ")");
	}
}

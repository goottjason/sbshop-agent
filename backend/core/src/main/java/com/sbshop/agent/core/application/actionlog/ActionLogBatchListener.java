package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

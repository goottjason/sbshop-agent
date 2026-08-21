package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionLogSyncListener {

	private final ActionLogService actionLogService;

	@EventListener
	public void onSyncCompleted(SyncCompletedEvent event) {
		String marketType = event.getMarketType().name();
		String actionType = marketType + "_SYNC";

		if (event.isSuccess()) {
			actionLogService.record(actionType, marketType, ActionStatus.SUCCESS, "동기화 성공");
		} else {
			String reason = event.getErrorMessage() != null ? event.getErrorMessage() : "원인 미상";
			actionLogService.record(actionType, marketType, ActionStatus.FAILED,
				"동기화 실패: " + reason);
		}
	}
}

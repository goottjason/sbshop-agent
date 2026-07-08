package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 동기화 완료 이벤트를 액션 로그로 자동 기록한다 (D-042).
 * 성공/실패를 각각 SUCCESS/FAILED로 남겨 진행 현황에서 결과를 확인할 수 있게 한다.
 * D-041과 결합 시 어댑터가 전파한 실패(403 등)가 여기서 명확히 기록된다.
 */
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

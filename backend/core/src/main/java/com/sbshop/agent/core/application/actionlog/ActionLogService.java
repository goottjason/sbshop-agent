package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.domain.actionlog.ActionLog;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.actionlog.repository.ActionLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionLogService {

	private final ActionLogRepository actionLogRepository;

	@Transactional
	public void record(String actionType, String marketType,
		ActionStatus actionStatus, String message) {
		try {
			actionLogRepository.save(ActionLog.builder()
				.actionType(actionType)
				.marketType(marketType)
				.actionStatus(actionStatus)
				.message(truncate(message))
				.build());
		} catch (Exception e) {
			log.warn("액션 로그 기록 실패 (actionType={}, status={}): {}",
				actionType, actionStatus, e.getMessage());
		}
	}

	@Transactional(readOnly = true)
	public List<ActionLog> recentLogs(int limit) {
		return recentLogs(0, limit);
	}

	@Transactional(readOnly = true)
	public List<ActionLog> recentLogs(int page, int size) {
		int safePage = Math.max(page, 0);
		int safeSize = size <= 0 ? 100 : Math.min(size, 500);
		return actionLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize));
	}

	private String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() > 1000 ? message.substring(0, 1000) : message;
	}
}

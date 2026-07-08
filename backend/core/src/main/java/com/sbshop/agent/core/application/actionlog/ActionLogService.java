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

/**
 * 사용자 액션 로그 기록/조회 서비스 (D-042).
 * 기록 실패가 본업(동기화 등)을 막지 않도록 예외 안전하게 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionLogService {

	private final ActionLogRepository actionLogRepository;

	/**
	 * 액션 로그 1건 저장. 저장 실패는 삼켜 로깅만 한다(본업 보호).
	 */
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

	/** 최근 액션 로그를 시간 역순으로 조회 (limit 개수 제한). */
	@Transactional(readOnly = true)
	public List<ActionLog> recentLogs(int limit) {
		int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
		return actionLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit));
	}

	/** message 컬럼 길이(1000) 초과 방지 */
	private String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() > 1000 ? message.substring(0, 1000) : message;
	}
}

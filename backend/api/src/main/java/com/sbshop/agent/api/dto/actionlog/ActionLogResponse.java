package com.sbshop.agent.api.dto.actionlog;

import com.sbshop.agent.core.domain.actionlog.ActionLog;
import java.time.LocalDateTime;

/**
 * 액션 로그 응답 DTO (D-042)
 */
public record ActionLogResponse(
	Long id,
	String actionType,
	String marketType,
	String actionStatus,
	String message,
	LocalDateTime createdAt) {

	public static ActionLogResponse from(ActionLog log) {
		return new ActionLogResponse(
			log.getId(),
			log.getActionType(),
			log.getMarketType(),
			log.getActionStatus() != null ? log.getActionStatus().name() : null,
			log.getMessage(),
			log.getCreatedAt());
	}
}

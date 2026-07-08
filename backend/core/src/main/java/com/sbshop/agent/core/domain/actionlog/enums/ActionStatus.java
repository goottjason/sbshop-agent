package com.sbshop.agent.core.domain.actionlog.enums;

/**
 * 사용자 액션 로그의 진행 상태. (D-042)
 * STARTED: 요청 접수, SUCCESS/FAILED: 처리 결과.
 */
public enum ActionStatus {
	STARTED,
	SUCCESS,
	FAILED
}

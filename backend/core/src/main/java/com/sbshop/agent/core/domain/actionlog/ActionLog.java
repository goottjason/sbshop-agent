package com.sbshop.agent.core.domain.actionlog;

import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.sql.Types;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * 사용자 액션 로그 (D-042)
 * 진행 현황 화면에 시간순으로 표시할 사용자 행동/결과 기록.
 * BaseEntity가 id/status/created_at/updated_at 제공 (created_at으로 시간순 정렬).
 */
@Entity
@Table(name = "sb_action_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionLog extends BaseEntity {

	/** 액션 종류 (예: COUPANG_SYNC, ORDER_CONFIRM) */
	@Column(name = "action_type", nullable = false, length = 50)
	private String actionType;

	/** 마켓 종류 (예: COUPANG). 마켓 무관 액션은 null */
	@Column(name = "market_type", length = 30)
	private String marketType;

	/** 진행 상태 STARTED/SUCCESS/FAILED */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "action_status", length = 20)
	private ActionStatus actionStatus;

	/** 설명 메시지 (예: "동기화 요청", "동기화 실패: 403 ...") */
	@Column(name = "message", length = 1000)
	private String message;

	@Builder
	public ActionLog(String actionType, String marketType,
		ActionStatus actionStatus, String message) {
		this.actionType = actionType;
		this.marketType = marketType;
		this.actionStatus = actionStatus;
		this.message = message;
	}
}

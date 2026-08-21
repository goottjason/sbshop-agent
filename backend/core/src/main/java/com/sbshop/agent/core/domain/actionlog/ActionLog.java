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

@Entity
@Table(name = "sb_action_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActionLog extends BaseEntity {

	@Column(name = "action_type", nullable = false, length = 50)
	private String actionType;

	@Column(name = "market_type", length = 30)
	private String marketType;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "action_status", length = 20)
	private ActionStatus actionStatus;

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

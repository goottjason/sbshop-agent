package com.sbshop.agent.core.domain.order.vo;

import java.sql.Types;

import org.hibernate.annotations.JdbcTypeCode;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 취소·반품·교환. 배송 단계와 독립된 축이라 서로 덮어쓰지 않는다.
 *
 * <p>마켓 원본 코드를 함께 보관한다 — 매핑이 틀렸을 때 마켓이 실제로 뭐라고 했는지
 * 되짚을 방법이 없으면 진단이 막힌다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClaimData {
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "claim_type", length = 20)
	private ClaimType claimType = ClaimType.NONE;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "claim_stage", length = 20)
	private ClaimStage claimStage = ClaimStage.NONE;

	/** 마켓이 준 원문 코드. 예: {@code E40} · {@code 221} · {@code EXCHANGE_REDELIVERING} */
	@Column(name = "claim_raw_code", length = 40)
	private String claimRawCode;

	@Builder(toBuilder = true)
	public ClaimData(ClaimType claimType, ClaimStage claimStage, String claimRawCode) {
		this.claimType = claimType != null ? claimType : ClaimType.NONE;
		this.claimStage = claimStage != null ? claimStage : ClaimStage.NONE;
		this.claimRawCode = claimRawCode;
	}

	public boolean isActive() {
		return claimType.isActive() && claimStage.isActive();
	}

	public boolean isRefundTerminal() {
		return claimType.isRefundTerminalAt(claimStage);
	}

	/** "반품 요청" 처럼 조합한 화면 라벨. 클레임이 없으면 {@code null}. */
	public String getLabel() {
		if (!claimType.isActive() || claimStage == ClaimStage.NONE) {
			return null;
		}
		return claimType.getLabel() + " " + claimStage.getLabel();
	}
}

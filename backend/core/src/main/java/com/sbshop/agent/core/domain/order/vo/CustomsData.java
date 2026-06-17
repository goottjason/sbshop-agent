package com.sbshop.agent.core.domain.order.vo;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomsData {
	/** 개인통관고유부호 (예: P로 시작하는 13자리 번호) */
	@Column(name = "customs_clearance_no", length = 50)
	private String customsClearanceNo;

	/** 통관 진행 상태 (진행중, 완료, 오류 등) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "customs_status", length = 20)
	private CustomsStatus customsStatus;

	/** 통관 검증된 사람 (수취인/주문자) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "verified_person", length = 20)
	private VerifiedPerson verifiedPerson;

	@Builder(toBuilder = true)
	public CustomsData(String customsClearanceNo, CustomsStatus customsStatus, VerifiedPerson verifiedPerson) {
		this.customsClearanceNo = customsClearanceNo;
		this.customsStatus = customsStatus != null ? customsStatus : CustomsStatus.PENDING;
		this.verifiedPerson = verifiedPerson;
	}
}

package com.sbshop.agent.core.domain.ledger;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_budget", uniqueConstraints = {
	@UniqueConstraint(name = "uk_ledger_budget", columnNames = {"year_month", "admin_user"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerBudget extends BaseEntity {

	@Column(name = "year_month", nullable = false, length = 7)
	private String yearMonth; // e.g. "2026-10"

	@Column(name = "admin_user", nullable = false, length = 50)
	private String adminUser; // "ALL", "admin1", "admin2", etc.

	@Column(name = "target_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal targetAmount;

	@Builder
	public LedgerBudget(String yearMonth, String adminUser, BigDecimal targetAmount) {
		this.yearMonth = yearMonth;
		this.adminUser = (adminUser == null || adminUser.isBlank()) ? "ALL" : adminUser;
		this.targetAmount = targetAmount;
	}

	public void updateTargetAmount(BigDecimal targetAmount) {
		this.targetAmount = targetAmount;
	}
}

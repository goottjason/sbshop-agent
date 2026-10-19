package com.sbshop.agent.core.domain.ledger;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_expense")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerExpense extends BaseEntity {

	@Column(name = "spent_date", nullable = false)
	private LocalDate spentDate;

	@Column(name = "amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "category", nullable = false, length = 50)
	private String category;

	@Column(name = "payment_source", nullable = false, length = 100)
	private String paymentSource;

	@Column(name = "memo", columnDefinition = "TEXT")
	private String memo;

	@Column(name = "admin_user", nullable = false, length = 50)
	private String adminUser;

	@Builder
	public LedgerExpense(LocalDate spentDate, BigDecimal amount, String category,
			String paymentSource, String memo, String adminUser) {
		this.spentDate = spentDate;
		this.amount = amount;
		this.category = category;
		this.paymentSource = paymentSource;
		this.memo = memo;
		this.adminUser = adminUser;
	}

	public void update(LocalDate spentDate, BigDecimal amount, String category,
			String paymentSource, String memo, String adminUser) {
		this.spentDate = spentDate;
		this.amount = amount;
		this.category = category;
		this.paymentSource = paymentSource;
		this.memo = memo;
		if (adminUser != null && !adminUser.isBlank()) {
			this.adminUser = adminUser;
		}
	}
}

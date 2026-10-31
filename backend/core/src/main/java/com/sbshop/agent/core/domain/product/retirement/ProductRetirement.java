package com.sbshop.agent.core.domain.product.retirement;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "sb_product_retirement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductRetirement extends BaseEntity {

	@jakarta.persistence.Version
	@Column(nullable = false)
	private long revision;

	@Column(name = "product_id", nullable = false, unique = true)
	private Long productId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "retirement_status", nullable = false, length = 20)
	private RetirementStatus retirementStatus;

	@Column(name = "esm_confirmed_at")
	private LocalDateTime esmConfirmedAt;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "last_attempt_at")
	private LocalDateTime lastAttemptAt;

	@Column(name = "last_report", columnDefinition = "TEXT")
	private String lastReport;

	public static ProductRetirement start(Long productId) {
		ProductRetirement retirement = new ProductRetirement();
		retirement.productId = productId;
		retirement.retirementStatus = RetirementStatus.IN_PROGRESS;
		return retirement;
	}

	public void resume() {
		if (retirementStatus != RetirementStatus.CANCELED)
			throw new IllegalStateException("취소된 퇴출만 재개할 수 있습니다: " + retirementStatus);
		retirementStatus = RetirementStatus.IN_PROGRESS;
	}

	public void confirmEsm(LocalDateTime at) {
		if (esmConfirmedAt == null)
			esmConfirmedAt = at;
	}

	public void recordAttempt(String report, LocalDateTime at) {
		attempts++;
		lastAttemptAt = at;
		lastReport = report;
	}

	public void complete() {
		retirementStatus = RetirementStatus.DONE;
	}

	public void cancel() {
		if (retirementStatus == RetirementStatus.DONE)
			throw new IllegalStateException("이미 끝난 퇴출은 취소할 수 없습니다.");
		retirementStatus = RetirementStatus.CANCELED;
	}

	public boolean inProgress() {
		return retirementStatus == RetirementStatus.IN_PROGRESS;
	}
}

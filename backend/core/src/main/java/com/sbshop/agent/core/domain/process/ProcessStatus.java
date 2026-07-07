package com.sbshop.agent.core.domain.process;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStep;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Entity
@Table(name = "sb_process_status", uniqueConstraints = @UniqueConstraint(columnNames = {"batch_id", "product_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessStatus extends BaseEntity {

	@Column(name = "batch_id", nullable = false, length = 50)
	private String batchId;

	@Column(name = "product_code", nullable = false, length = 50)
	private String productCode;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "job_type", length = 50)
	private JobType jobType;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "step", length = 50)
	private ProcessStep step;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "process_status", length = 20)
	private ProcessStatusType processStatus;

	@Column(name = "message", length = 1000)
	private String message;

	@Column(name = "details", columnDefinition = "text")
	private String details;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "updated_at_extra")
	private LocalDateTime updatedAtExtra;

	@Builder
	public ProcessStatus(String batchId, String productCode, JobType jobType, ProcessStep step,
		ProcessStatusType processStatus, String message, String details, LocalDateTime startedAt) {
		this.batchId = batchId;
		this.productCode = productCode;
		this.jobType = jobType;
		this.step = step;
		this.processStatus = processStatus;
		this.message = message;
		this.details = details;
		this.startedAt = startedAt;
	}

	public void updateStep(ProcessStep step, ProcessStatusType processStatus, String message) {
		this.step = step;
		this.processStatus = processStatus;
		this.message = message;
		this.updatedAtExtra = LocalDateTime.now();
	}

	public void mergeChannelResult(String channelResult) {
		if (this.details == null || this.details.isEmpty()) {
			this.details = channelResult;
		} else {
			this.details = this.details + "," + channelResult;
		}
		this.updatedAtExtra = LocalDateTime.now();
	}
}

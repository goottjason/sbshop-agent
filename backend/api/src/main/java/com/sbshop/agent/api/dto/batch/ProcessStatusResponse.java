package com.sbshop.agent.api.dto.batch;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import java.time.LocalDateTime;

public record ProcessStatusResponse(
	Long id,
	String status,
	LocalDateTime createdAt,
	LocalDateTime updatedAt,
	String batchId,
	String productCode,
	String jobType,
	String step,
	String processStatus,
	String message,
	String details,
	LocalDateTime startedAt,
	LocalDateTime updatedAtExtra) {

	public static ProcessStatusResponse from(ProcessStatus s) {
		return new ProcessStatusResponse(
			s.getId(),
			s.getStatus() != null ? s.getStatus().name() : null,
			s.getCreatedAt(),
			s.getUpdatedAt(),
			s.getBatchId(),
			s.getProductCode(),
			s.getJobType() != null ? s.getJobType().name() : null,
			s.getStep() != null ? s.getStep().name() : null,
			s.getProcessStatus() != null ? s.getProcessStatus().name() : null,
			s.getMessage(),
			s.getDetails(),
			s.getStartedAt(),
			s.getUpdatedAtExtra());
	}
}

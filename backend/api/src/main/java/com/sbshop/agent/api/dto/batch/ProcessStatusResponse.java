package com.sbshop.agent.api.dto.batch;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import java.time.LocalDateTime;

/**
 * F-BATCH-S1: 배치 진행상태 응답 DTO.
 *
 * 기존에는 {@code /api/v1/products/batch/status/{batchId}}가 도메인 엔티티 {@link ProcessStatus}를
 * 직접 직렬화했다. 프론트 소비 JSON 계약(필드명·형태)을 그대로 미러한다 — 엔티티 @Getter(BaseEntity 포함)
 * 로 노출되던 필드를 동일 이름으로 담고 enum은 종전 직렬화 형태(name())와 동일하게 문자열로 매핑한다.
 */
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

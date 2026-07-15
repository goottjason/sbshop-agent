package com.sbshop.agent.api.dto.supplier;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.time.LocalDateTime;

/**
 * F-SUP-1: GET /suppliers 응답 DTO. Supplier 엔티티를 직접 노출하지 않고 스칼라 필드만 미러한다.
 * LAZY @ManyToOne currency 는 노출하지 않는다(지연로딩 유출 차단).
 */
public record SupplierResponse(
	Long id,
	String supplierCode,
	String supplierName,
	RecordStatus status,
	LocalDateTime createdAt,
	LocalDateTime updatedAt) {

	public static SupplierResponse from(Supplier s) {
		return new SupplierResponse(
			s.getId(),
			s.getSupplierCode(),
			s.getSupplierName(),
			s.getStatus(),
			s.getCreatedAt(),
			s.getUpdatedAt());
	}
}

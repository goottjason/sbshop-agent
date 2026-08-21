package com.sbshop.agent.api.dto.supplier;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.time.LocalDateTime;

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

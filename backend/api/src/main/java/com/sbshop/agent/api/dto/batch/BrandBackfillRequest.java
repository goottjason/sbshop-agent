package com.sbshop.agent.api.dto.batch;

public record BrandBackfillRequest(
	String supplierCode,
	Integer limit) {
}

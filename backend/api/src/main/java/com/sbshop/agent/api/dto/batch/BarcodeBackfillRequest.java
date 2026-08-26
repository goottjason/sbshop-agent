package com.sbshop.agent.api.dto.batch;

public record BarcodeBackfillRequest(
	String supplierCode,
	Integer limit) {
}

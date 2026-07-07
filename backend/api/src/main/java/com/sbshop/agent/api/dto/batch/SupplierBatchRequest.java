package com.sbshop.agent.api.dto.batch;

import java.math.BigDecimal;

public record SupplierBatchRequest(
	String supplierCode,
	BigDecimal marginRate,
	BigDecimal couponRate,
	BigDecimal minMarginPrice) {
}

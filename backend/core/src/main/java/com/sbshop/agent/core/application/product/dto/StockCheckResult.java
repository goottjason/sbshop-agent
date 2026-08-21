package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record StockCheckResult(
	StockStatus status,
	BigDecimal costPrice,
	Integer stock,
	LocalDate restockDate,
	boolean sourceGone,
	BigDecimal shippingCost) {
	public StockCheckResult(StockStatus status, BigDecimal costPrice, Integer stock, LocalDate restockDate,
		boolean sourceGone) {
		this(status, costPrice, stock, restockDate, sourceGone, null);
	}

	public StockCheckResult(StockStatus status, BigDecimal costPrice, Integer stock, LocalDate restockDate) {
		this(status, costPrice, stock, restockDate, false, null);
	}
}

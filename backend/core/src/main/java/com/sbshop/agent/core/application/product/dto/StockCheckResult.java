package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 크롤링 결과를 담는 DTO.
 * 재고 상태 뿐 아니라 가격, 재고 수량, 입고예정일 등 최신 정보를 포함합니다.
 */
public record StockCheckResult(
	StockStatus status,
	BigDecimal costPrice,
	Integer stock,
	LocalDate restockDate) {
}

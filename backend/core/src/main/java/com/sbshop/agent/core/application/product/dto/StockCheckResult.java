package com.sbshop.agent.core.application.product.dto;

import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 크롤링 결과를 담는 DTO.
 * 재고 상태 뿐 아니라 가격, 재고 수량, 입고예정일 등 최신 정보를 포함합니다.
 *
 * <p>{@code sourceGone}: 소스 상품 페이지가 사라진 경우(예: F&amp;M 404 — 더 이상 판매하지 않음).
 * true면 배치는 가격을 재산정/변경하지 않고 재고만 품절 처리한다(오가격 방지).
 */
public record StockCheckResult(
	StockStatus status,
	BigDecimal costPrice,
	Integer stock,
	LocalDate restockDate,
	boolean sourceGone) {

	/** 기존 4-인자 호출 하위호환(sourceGone=false). iHerb 등 정상 소스 경로가 사용. */
	public StockCheckResult(StockStatus status, BigDecimal costPrice, Integer stock, LocalDate restockDate) {
		this(status, costPrice, stock, restockDate, false);
	}
}

package com.sbshop.agent.core.application.sourcing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 국내 쇼핑 검색 통계.
 *
 * @param totalCount 검색 결과 상품 수 — 경쟁강도. 클수록 레드오션.
 * @param lowestPrice 최저가(원) — 우리 예상 판매가와 비교해 가격경쟁력을 잰다.
 * @param topCategories 상위 노출 상품의 카테고리 경로 — 마켓 카테고리 매핑 힌트로 쓴다.
 */
public record ShoppingStats(
	String query,
	int totalCount,
	BigDecimal lowestPrice,
	BigDecimal medianPrice,
	List<String> topCategories) {
}

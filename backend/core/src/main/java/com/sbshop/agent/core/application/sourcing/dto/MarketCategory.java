package com.sbshop.agent.core.application.sourcing.dto;

/**
 * 마켓 카테고리 해석 결과.
 *
 * @param categoryId   마켓 식별자 (쿠팡 displayCategoryCode / 스토어 leafCategoryId /
 *                     11번가 dispCtgrNo / Cafe24 category_no)
 * @param categoryPath 사람이 읽을 경로 ("건강기능식품 > 유산균"). 검수 화면 표시용.
 * @param confident    자동 매칭을 신뢰할 수 있는가. false면 사용자 확인이 필요하다.
 */
public record MarketCategory(String categoryId, String categoryPath, boolean confident) {

	public static MarketCategory unresolved() {
		return new MarketCategory(null, null, false);
	}

	public boolean isResolved() {
		return categoryId != null && !categoryId.isBlank();
	}
}

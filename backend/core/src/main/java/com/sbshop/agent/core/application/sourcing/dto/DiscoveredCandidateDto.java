package com.sbshop.agent.core.application.sourcing.dto;

import java.math.BigDecimal;

/**
 * 베스트셀러 목록 카드 1장에서 뽑은 후보 원재료.
 *
 * <p>스크래퍼 사이드카(sbshop-scraper) 응답의 도메인 표현이다. 목록 카드 하나에
 * 스코어링에 필요한 신호가 전부 들어 있어 발굴 단계에서 상세 페이지를 열지 않는다.
 *
 * @param discountPrice 실제 매입가(원). kr.iherb.com은 원화 표기라 환산하지 않는다.
 * @param sales30d      "30일 동안 N개 판매" 파싱값. iHerb가 노출할 때만 존재(null 가능).
 * @param sponsored     광고 노출 — 랭킹이 유기적 인기가 아니므로 랭킹 점수를 신뢰할 수 없다.
 */
public record DiscoveredCandidateDto(
	String vendor,
	String externalId,
	String sourceUrl,
	String partNumber,
	String brand,
	String brandCode,
	String nameKo,
	String categorySlug,
	String imageUrl,
	BigDecimal listPrice,
	BigDecimal discountPrice,
	Integer discountPct,
	BigDecimal rating,
	Integer reviewCount,
	Integer sales30d,
	Integer rankPosition,
	boolean sponsored,
	boolean outOfStock,
	boolean discontinued) {
}

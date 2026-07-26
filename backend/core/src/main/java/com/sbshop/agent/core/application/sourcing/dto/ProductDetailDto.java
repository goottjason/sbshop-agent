package com.sbshop.agent.core.application.sourcing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 상품 상세 크롤 결과 — 통관 게이트(성분표)와 마켓 필수필드(중량·바코드·수량)의 원천.
 *
 * @param ok             성분 추출까지 성공했는가. false면 통관 판정을 PASS로 내리면 안 된다.
 * @param ingredientsRaw 성분 원문(한글). kr.iherb.com은 한글 성분표를 주므로
 *                       식약처 반입차단 목록(한글)과 바로 대조할 수 있다.
 */
public record ProductDetailDto(
	boolean ok,
	String status,
	String sourceUrl,
	String externalId,
	String nameKo,
	String brandKo,
	String brandCode,
	String rootCategory,
	boolean discontinued,
	String partNumber,
	String upc,
	BigDecimal priceKrw,
	BigDecimal listPriceKrw,
	Boolean inStock,
	BigDecimal shippingWeightGrams,
	Integer packageQuantity,
	String dimensions,
	String ingredientsRaw,
	String mainIngredients,
	String otherIngredients,
	String description,
	String usage,
	String caution,
	List<String> images,
	String error) {
}

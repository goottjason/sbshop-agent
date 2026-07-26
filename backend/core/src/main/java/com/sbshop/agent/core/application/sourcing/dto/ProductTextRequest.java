package com.sbshop.agent.core.application.sourcing.dto;

/**
 * 상품 텍스트 생성 요청 (LLM 입력).
 *
 * <p>상품 1건당 <b>한 번만</b> 호출한다. 마켓별 상품명은 여기서 받은 기본명에 브랜드·용량·묶음수를
 * 조합하고 마켓 글자수에 맞춰 자르는 규칙으로 만든다 — 마켓마다 LLM을 부르면 4배 비싸고
 * 마켓 간 상품명이 제각각이 되어 관리가 어렵다.
 */
public record ProductTextRequest(
	String originalNameKo,
	String brand,
	String brandKo,
	String rootCategory,
	String ingredientsSummary,
	Integer packageQuantity,
	String measureUnitDesc) {
}

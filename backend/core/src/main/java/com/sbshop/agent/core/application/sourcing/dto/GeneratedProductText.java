package com.sbshop.agent.core.application.sourcing.dto;

import java.util.List;

/**
 * LLM이 생성한 상품 텍스트.
 *
 * @param baseName     한글 기본 상품명(브랜드·용량·묶음수 제외한 핵심부)
 * @param keywords     검색 키워드
 * @param categoryHint "건강기능식품 > 유산균" 형태의 카테고리 힌트. 마켓 카테고리 매핑 보조.
 * @param generatedBy  실제로 응답한 모델 ID(폴백 추적용). 규칙기반이면 "rule-based".
 */
public record GeneratedProductText(
	String baseName,
	List<String> keywords,
	String categoryHint,
	String generatedBy) {
}

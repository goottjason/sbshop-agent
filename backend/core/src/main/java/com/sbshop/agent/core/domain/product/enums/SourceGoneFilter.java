package com.sbshop.agent.core.domain.product.enums;

/**
 * 폐기 후보(원본 소멸) 여부로 상품 목록을 거르는 조건.
 *
 * <p>품절과 다르다 — 품절은 되돌아오지만 폐기 후보는 원본 링크가 죽었거나 단종된 것이다.
 * 관리자가 이것만 골라 보고 삭제할 수 있어야 한다.
 */
public enum SourceGoneFilter {
	/** 구분 없이 전체 (기본값) */
	ALL,
	/** 폐기 후보만 — source_gone_at 이 있는 것 */
	GONE_ONLY,
	/** 정상만 — source_gone_at 이 없는 것 */
	ALIVE_ONLY
}

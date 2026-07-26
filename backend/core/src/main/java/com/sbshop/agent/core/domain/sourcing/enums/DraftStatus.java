package com.sbshop.agent.core.domain.sourcing.enums;

/** 등록 초안의 상태. */
public enum DraftStatus {

	/** 인리치먼트(상품명·키워드·카테고리·가격 생성) 진행 중. */
	ENRICHING,

	/** 검수 가능. 마켓별 필수필드 검사 결과가 채워져 있다. */
	READY,

	/** 마켓 등록 진행 중. */
	PUBLISHING,

	/** 모든 대상 마켓 등록 완료. */
	PUBLISHED,

	/** 일부/전체 마켓 등록 실패. 실패 마켓만 재시도할 수 있다. */
	FAILED
}

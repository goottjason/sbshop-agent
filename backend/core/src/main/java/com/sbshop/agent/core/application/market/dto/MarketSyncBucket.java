package com.sbshop.agent.core.application.market.dto;

public enum MarketSyncBucket {

	MATCHED("일치"),
	IDENTIFIER_MISMATCH("식별자 불일치"),
	MISSING_LOCAL("마켓에만 존재"),
	STALE_LOCAL("우리에만 존재"),
	UNJOINABLE_LOCAL("대조 불가(SB코드·식별자 모두 없음)"),
	DUPLICATE_MARKET("마켓 중복 리스팅");

	private final String label;

	MarketSyncBucket(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}

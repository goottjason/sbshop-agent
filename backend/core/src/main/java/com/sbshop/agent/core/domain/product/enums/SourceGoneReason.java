package com.sbshop.agent.core.domain.product.enums;

/**
 * 원본 소싱처에서 상품을 더 이상 구할 수 없는 이유.
 *
 * <p>"품절"과 다르다 — 품절은 되돌아오지만 이쪽은 <b>폐기 후보</b>다.
 * 사유를 나누는 이유는 사람이 할 일이 다르기 때문이다:
 * {@code LINK_DEAD} 는 URL 을 고치면 살아날 수 있고, {@code DISCONTINUED} 는 대체품을 찾아야 한다.
 */
public enum SourceGoneReason {
	/** 소싱 URL 이 404 — 링크가 죽었다. URL 변경일 수도 있다. */
	LINK_DEAD,
	/** 페이지는 있으나 판매 종료·단종으로 표기돼 있다. */
	DISCONTINUED
}

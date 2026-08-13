package com.sbshop.agent.api.dto.product;

/**
 * 상품 그리드 마켓 배지 1칸의 서버 상태.
 *
 * <p>맵에 <b>키가 없으면 미등록</b>이다(클릭하면 등록). 키가 있으면 등록된 것이고,
 * {@code status}로 등록 완료(SYNCED)와 미완료(PENDING)를 가른다.
 *
 * <p>실패(FAILED)는 여기 담지 않는다 — {@code sb_market_registration}에 실패를 저장하는 컬럼이 없고,
 * 등록 실패는 등록행을 남기지 않거나 PENDING으로 남긴다. 클릭 실패는 화면 세션 상태로만 표시한다.
 *
 * @param status "SYNCED"(등록 완료) 또는 "PENDING"(등록행은 있으나 동기화 미완료)
 * @param url    마켓 상품페이지 URL. 링크 식별자를 아직 확보하지 못했으면 null.
 */
public record MarketBadgeState(String status, String url) {

	public static final String SYNCED = "SYNCED";
	public static final String PENDING = "PENDING";

	public static MarketBadgeState of(boolean synced, String url) {
		return new MarketBadgeState(synced ? SYNCED : PENDING, (url == null || url.isBlank()) ? null : url);
	}
}

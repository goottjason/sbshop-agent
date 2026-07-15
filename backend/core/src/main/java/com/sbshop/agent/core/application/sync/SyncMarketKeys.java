package com.sbshop.agent.core.application.sync;

/**
 * 동기화 상태(sb_market_sync_status.market_type) 키 상수(F-SYNC-2).
 *
 * 상태 기록이 스케줄러(worker)에서 각 sync 서비스(core)로 이동함에 따라, 서비스와 스케줄러가
 * 동일한 키 문자열을 쓰도록 한 곳에 모은다. 값은 기존 스케줄러가 쓰던 문자열과 동일해야
 * /orders/sync/status 응답 계약이 유지된다.
 */
public final class SyncMarketKeys {

	public static final String COUPANG = "COUPANG";
	public static final String SMART_STORE = "SMART_STORE";
	public static final String ELEVEN_STREET = "ELEVEN_STREET";
	public static final String GMARKET = "GMARKET";
	public static final String COUPANG_SETTLEMENT = "COUPANG_SETTLEMENT";
	public static final String CUSTOMS = "CUSTOMS";

	private SyncMarketKeys() {}
}

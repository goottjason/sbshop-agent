package com.sbshop.agent.core.application.market.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.LinkedHashSet;
import java.util.List;

public record MarketSyncReportRequest(
	List<MarketType> markets,
	int sampleLimit,
	boolean deep,
	int deepLimit,
	long throttleMs,
	boolean liveInventory,
	int liveLimit,
	boolean persist) {

	public static final int DEFAULT_SAMPLE_LIMIT = 20;
	public static final int MAX_SAMPLE_LIMIT = 500;
	public static final int DEFAULT_DEEP_LIMIT = 200;
	public static final int MAX_DEEP_LIMIT = 1000;
	public static final long DEFAULT_THROTTLE_MS = 250L;
	public static final long MAX_THROTTLE_MS = 5000L;
	public static final int DEFAULT_LIVE_LIMIT = 50;
	public static final int MAX_LIVE_LIMIT = 5000;

	public static final List<MarketType> DEFAULT_MARKETS = List.of(
		MarketType.COUPANG,
		MarketType.SMART_STORE,
		MarketType.ELEVEN_STREET,
		MarketType.CAFE24,
		MarketType.GMARKET,
		MarketType.AUCTION);

	public static MarketSyncReportRequest of(
		List<MarketType> markets, Integer sampleLimit, Boolean deep, Integer deepLimit, Long throttleMs) {
		return of(markets, sampleLimit, deep, deepLimit, throttleMs, null, null);
	}

	public static MarketSyncReportRequest of(
		List<MarketType> markets, Integer sampleLimit, Boolean deep, Integer deepLimit, Long throttleMs,
		Boolean liveInventory, Integer liveLimit) {
		return of(markets, sampleLimit, deep, deepLimit, throttleMs, liveInventory, liveLimit, null);
	}

	public static MarketSyncReportRequest of(
		List<MarketType> markets, Integer sampleLimit, Boolean deep, Integer deepLimit, Long throttleMs,
		Boolean liveInventory, Integer liveLimit, Boolean persist) {
		return new MarketSyncReportRequest(
			resolveMarkets(markets),
			clamp(sampleLimit, DEFAULT_SAMPLE_LIMIT, 0, MAX_SAMPLE_LIMIT),
			Boolean.TRUE.equals(deep),
			clamp(deepLimit, DEFAULT_DEEP_LIMIT, 0, MAX_DEEP_LIMIT),
			clamp(throttleMs, DEFAULT_THROTTLE_MS, 0L, MAX_THROTTLE_MS),
			Boolean.TRUE.equals(liveInventory),
			clamp(liveLimit, DEFAULT_LIVE_LIMIT, 0, MAX_LIVE_LIMIT),
			Boolean.TRUE.equals(persist));
	}

	private static List<MarketType> resolveMarkets(List<MarketType> requested) {
		if (requested == null || requested.isEmpty()) {
			return DEFAULT_MARKETS;
		}
		LinkedHashSet<MarketType> distinct = new LinkedHashSet<>();
		for (MarketType market : requested) {
			if (market != null && market != MarketType.UNKNOWN) {
				distinct.add(market);
			}
		}
		return distinct.isEmpty() ? DEFAULT_MARKETS : List.copyOf(distinct);
	}

	private static int clamp(Integer value, int fallback, int min, int max) {
		if (value == null) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static long clamp(Long value, long fallback, long min, long max) {
		if (value == null) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}

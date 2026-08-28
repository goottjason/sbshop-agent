package com.sbshop.agent.core.application.sync;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

public final class SyncFreshnessPolicy {

	private static final Duration DEFAULT_THRESHOLD = Duration.ofDays(7);

	private static final Map<String, Duration> THRESHOLDS = Map.of(
		SyncMarketKeys.GMARKET, Duration.ofDays(10),
		SyncMarketKeys.COUPANG, Duration.ofDays(2),
		SyncMarketKeys.SMART_STORE, Duration.ofDays(2),
		SyncMarketKeys.ELEVEN_STREET, Duration.ofDays(7));

	private SyncFreshnessPolicy() {}

	public static Duration defaultThreshold() {
		return DEFAULT_THRESHOLD;
	}

	public static Duration threshold(String marketKey) {
		return THRESHOLDS.getOrDefault(marketKey, DEFAULT_THRESHOLD);
	}

	public static Optional<Duration> staleness(String marketKey, LocalDateTime lastNewAt,
		LocalDateTime now) {
		if (lastNewAt == null) {
			return Optional.empty();
		}
		Duration elapsed = Duration.between(lastNewAt, now);
		return elapsed.compareTo(threshold(marketKey)) > 0 ? Optional.of(elapsed) : Optional.empty();
	}
}

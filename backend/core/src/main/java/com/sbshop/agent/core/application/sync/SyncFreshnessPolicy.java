package com.sbshop.agent.core.application.sync;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

public final class SyncFreshnessPolicy {

	public static final int WINDOW_DAYS = 90;

	private static final int MULTIPLIER = 3;
	private static final int MIN_DAYS = 2;
	private static final int MAX_DAYS = 30;
	private static final long SECONDS_PER_DAY = 86400L;

	private SyncFreshnessPolicy() {}

	public static Duration threshold(long ordersInWindow, int windowDays) {
		if (ordersInWindow <= 0) {
			return Duration.ofDays(MAX_DAYS);
		}
		double avgIntervalDays = windowDays / (double) ordersInWindow;
		double thresholdDays = Math.min(MAX_DAYS, Math.max(MIN_DAYS, MULTIPLIER * avgIntervalDays));
		return Duration.ofSeconds(Math.round(thresholdDays * SECONDS_PER_DAY));
	}

	public static Optional<Duration> staleness(long ordersInWindow, int windowDays,
		LocalDateTime lastNewAt, LocalDateTime now) {
		if (lastNewAt == null) {
			return Optional.empty();
		}
		Duration elapsed = Duration.between(lastNewAt, now);
		Duration threshold = threshold(ordersInWindow, windowDays);
		return elapsed.compareTo(threshold) > 0 ? Optional.of(elapsed) : Optional.empty();
	}
}

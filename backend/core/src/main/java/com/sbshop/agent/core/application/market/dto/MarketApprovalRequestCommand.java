package com.sbshop.agent.core.application.market.dto;

import java.util.LinkedHashSet;
import java.util.List;

public record MarketApprovalRequestCommand(List<String> marketItemIds, long throttleMs) {

	public static final int MAX_TARGETS = 20;
	public static final long DEFAULT_THROTTLE_MS = 600L;
	public static final long MIN_THROTTLE_MS = 600L;
	public static final long MAX_THROTTLE_MS = 10_000L;

	public static MarketApprovalRequestCommand of(List<String> marketItemIds, Long throttleMs) {
		if (marketItemIds == null || marketItemIds.isEmpty()) {
			throw new IllegalArgumentException("승인 요청 대상 ID를 지정하세요 — 전건 일괄 요청은 지원하지 않습니다");
		}
		if (marketItemIds.size() > MAX_TARGETS) {
			throw new IllegalArgumentException("승인 요청은 한 번에 최대 " + MAX_TARGETS + "건입니다 (요청 "
				+ marketItemIds.size() + "건) — 전건 일괄 요청은 지원하지 않습니다");
		}
		LinkedHashSet<String> distinct = new LinkedHashSet<>();
		for (String id : marketItemIds) {
			if (id != null && !id.isBlank()) {
				distinct.add(id.trim());
			}
		}
		if (distinct.isEmpty()) {
			throw new IllegalArgumentException("승인 요청 대상 ID가 모두 비어 있습니다");
		}
		return new MarketApprovalRequestCommand(List.copyOf(distinct), clamp(throttleMs));
	}

	private static long clamp(Long throttleMs) {
		if (throttleMs == null) {
			return DEFAULT_THROTTLE_MS;
		}
		return Math.max(MIN_THROTTLE_MS, Math.min(MAX_THROTTLE_MS, throttleMs));
	}
}

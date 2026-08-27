package com.sbshop.agent.core.application.market.dto;

import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalOutcome;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalResult;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import java.util.List;

public record MarketApprovalReport(
	LocalDateTime requestedAt,
	MarketType market,
	int submitted,
	int called,
	int requested,
	int skipped,
	int retryable,
	int failed,
	long throttleMs,
	long elapsedMs,
	List<MarketApprovalResult> items) {

	public static MarketApprovalReport of(MarketType market, long throttleMs, long elapsedMs,
		List<MarketApprovalResult> items) {
		List<MarketApprovalResult> copied = List.copyOf(items);
		return new MarketApprovalReport(LocalDateTime.now(), market, copied.size(),
			(int)copied.stream().filter(MarketApprovalResult::called).count(),
			count(copied, MarketApprovalOutcome.REQUESTED),
			count(copied, MarketApprovalOutcome.SKIPPED),
			count(copied, MarketApprovalOutcome.RETRYABLE),
			count(copied, MarketApprovalOutcome.FAILED),
			throttleMs, elapsedMs, copied);
	}

	private static int count(List<MarketApprovalResult> items, MarketApprovalOutcome outcome) {
		return (int)items.stream().filter(item -> item.outcome() == outcome).count();
	}
}

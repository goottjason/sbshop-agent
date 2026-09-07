package com.sbshop.agent.core.domain.market.client.dto;

import java.util.Map;

/** Fixed provider values, not a receipt. Product PUT must be followed by independent field readback. */
public record PreparedMarketFields(String accountReference, String resolvedOptionId, boolean requiresApproval,
	Map<String, String> expectedValues, String payload) {
	public PreparedMarketFields {
		if (accountReference == null || accountReference.isBlank() || expectedValues == null || expectedValues.isEmpty()
			|| payload == null || payload.isBlank())
			throw new IllegalArgumentException("필드 전송의 계정·목표값·요청이 필요합니다.");
		expectedValues = Map.copyOf(expectedValues);
	}
}

package com.sbshop.agent.core.domain.market.client.dto;

import java.util.Map;

/** An observation is confirmed only for the requested fields and its exact account/listing/option. */
public record MarketFieldsRead(Map<String, String> values, String accountReference, String resolvedOptionId,
	Approval approval, String writeBlockReason) {
	public enum Approval {
		NOT_REQUIRED, APPROVED, PENDING, REJECTED, UNKNOWN
	}

	public MarketFieldsRead {
		values = Map.copyOf(values);
	}
}

package com.sbshop.agent.core.domain.market.client.dto;

import java.util.List;

public record ListingAttributeRepair(String marketItemId, String statusBefore, List<String> filled,
	List<String> removed, List<String> missing, ListingAttributeRepairOutcome outcome, String detail) {
}

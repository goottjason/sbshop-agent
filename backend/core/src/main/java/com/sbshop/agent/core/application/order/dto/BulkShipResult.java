package com.sbshop.agent.core.application.order.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BulkShipResult {
	private int successCount;
	private int failedCount;
	private int skippedCount;
	private List<Long> failedIds;
	private List<String> errors;
}

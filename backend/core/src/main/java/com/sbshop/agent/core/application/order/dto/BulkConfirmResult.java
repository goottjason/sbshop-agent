package com.sbshop.agent.core.application.order.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BulkConfirmResult {
	private int successCount;
	private int failedCount;
	private List<Long> failedIds;
	private List<String> errors;
}

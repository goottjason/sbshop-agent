package com.sbshop.agent.api.dto.batch;

import java.math.BigDecimal;
import java.util.List;

public record ManualUpdateRequest(
		List<Long> productIds,
		List<BigDecimal> prices,
		List<Integer> stocks) {
}

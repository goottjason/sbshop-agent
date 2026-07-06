package com.sbshop.agent.api.dto.batch;

import java.math.BigDecimal;
import java.util.List;

public record CrawlAndUpdateRequest(
		List<Long> productIds,
		BigDecimal marginRate,
		BigDecimal couponRate,
		BigDecimal minMarginPrice) {
}

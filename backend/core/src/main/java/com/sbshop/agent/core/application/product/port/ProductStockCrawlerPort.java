package com.sbshop.agent.core.application.product.port;

import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.product.enums.StockStatus;

public interface ProductStockCrawlerPort {
	StockStatus checkStockStatus(String sourceUrl);

	StockCheckResult checkStockWithDetails(String sourceUrl);
}

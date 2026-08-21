package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.domain.order.enums.MarketType;

public interface MarketCategoryResolverPort {
	MarketType market();

	MarketCategory resolve(String categoryHint, String productName, String brand);
}

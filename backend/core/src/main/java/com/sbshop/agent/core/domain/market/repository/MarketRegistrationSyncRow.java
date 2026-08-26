package com.sbshop.agent.core.domain.market.repository;

import java.math.BigDecimal;

public interface MarketRegistrationSyncRow {

	Long getProductId();

	String getSbCode();

	String getMarketIdentifiers();

	BigDecimal getLocalSalePrice();
}

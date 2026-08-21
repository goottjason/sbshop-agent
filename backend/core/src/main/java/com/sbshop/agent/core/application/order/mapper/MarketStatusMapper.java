package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;

public interface MarketStatusMapper {
	MarketType getMarketType();

	ShippingStatus mapStatus(Map<String, String> marketStatuses);
}

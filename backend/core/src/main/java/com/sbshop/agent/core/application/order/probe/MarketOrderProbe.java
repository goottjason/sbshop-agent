package com.sbshop.agent.core.application.order.probe;

import java.util.List;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;

public interface MarketOrderProbe {
	List<MarketType> marketTypes();

	OrderProbeResult probe(Order order);
}

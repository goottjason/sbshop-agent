package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

public interface MarketAccountResourcePort {
	MarketType market();

	Map<String, String> resolve();

	void invalidate();
}

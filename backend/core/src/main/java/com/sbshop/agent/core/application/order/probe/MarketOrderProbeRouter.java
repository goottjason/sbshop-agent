package com.sbshop.agent.core.application.order.probe;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;

@Component
public class MarketOrderProbeRouter {
	private final Map<MarketType, MarketOrderProbe> probes = new EnumMap<>(MarketType.class);

	public MarketOrderProbeRouter(List<MarketOrderProbe> registered) {
		for (MarketOrderProbe probe : registered) {
			for (MarketType marketType : probe.marketTypes()) {
				probes.put(marketType, probe);
			}
		}
	}

	public boolean has(MarketType marketType) {
		return probes.containsKey(marketType);
	}

	public OrderProbeResult probe(MarketType marketType, Order order) {
		MarketOrderProbe probe = probes.get(marketType);
		if (probe == null) {
			return OrderProbeResult.unknown("프로브 없음: " + marketType);
		}
		return probe.probe(order);
	}
}

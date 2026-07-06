package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MarketClientRouter {

	private final Map<MarketType, MarketClient> adapterMap;

	public MarketClientRouter(List<MarketClient> adapters) {
		this.adapterMap = adapters.stream()
				.collect(Collectors.toMap(MarketClient::getSupportedMarket, adapter -> adapter));
	}

	public MarketClient getClient(MarketType marketType) {
		MarketClient adapter = adapterMap.get(marketType);
		if (adapter == null) {
			throw new IllegalArgumentException("지원하지 않는 마켓입니다: " + marketType);
		}
		return adapter;
	}

	public boolean hasClient(MarketType marketType) {
		return adapterMap.containsKey(marketType);
	}
}

package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MarketClientRouter {

	private final Map<MarketType, MarketClient> adapterMap;

	public MarketClientRouter(List<MarketClient> adapters,
		com.sbshop.agent.core.application.market.MarketConnectionWriteGuard guard) {
		this.adapterMap = adapters.stream()
			.collect(Collectors.toMap(MarketClient::getSupportedMarket, adapter -> guarded(adapter, guard)));
	}

	private MarketClient guarded(MarketClient adapter,
		com.sbshop.agent.core.application.market.MarketConnectionWriteGuard guard) {
		return (MarketClient)java.lang.reflect.Proxy.newProxyInstance(MarketClient.class.getClassLoader(),
			new Class<?>[] {MarketClient.class}, (proxy, method, args) -> {
				if ("submitPreparedPublication".equals(method.getName()))
					guard.requirePublicationIntent(adapter.getSupportedMarket(),
						(com.sbshop.agent.core.domain.product.Product)args[0], (String)args[1]);
				if (com.sbshop.agent.core.application.market.MarketConnectionWriteGuard.WRITES
					.contains(method.getName()))
					guard.requireWritable(adapter.getSupportedMarket(), args == null ? new Object[0] : args);
				try {
					return method.invoke(adapter, args);
				} catch (java.lang.reflect.InvocationTargetException e) {
					throw e.getCause();
				}
			});
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

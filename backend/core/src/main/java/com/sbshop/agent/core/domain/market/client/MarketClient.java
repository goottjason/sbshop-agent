package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.List;
import java.util.Map;

public interface MarketClient {

	MarketType getSupportedMarket();

	Map<String, String> publish(Product product);

	MarketItemInfo extractMarketItem(String marketItemId);

	MarketItemInfo parseLocalData(Map<String, Object> rawData);

	Map<String, Object> syncPriceAndStock(
			String marketItemId,
			Map<String, Object> currentRawData,
			Integer price,
			Integer stock);

	Map<String, Object> syncImagesAndHtml(
			String marketItemId,
			Map<String, Object> currentRawData,
			List<String> hostedImages,
			String newDetailHtml);
}

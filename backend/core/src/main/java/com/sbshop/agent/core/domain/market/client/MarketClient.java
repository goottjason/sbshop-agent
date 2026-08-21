package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MarketClient {

	MarketType getSupportedMarket();

	Map<String, String> publish(Product product);

	default Map<String, String> publish(Product product, MarketPublishContext context) {
		return publish(product);
	}

	MarketItemInfo extractMarketItem(String marketItemId);

	MarketItemInfo parseLocalData(Map<String, Object> rawData);

	Map<String, Object> syncPriceAndStock(
		String marketItemId,
		Map<String, Object> currentRawData,
		Integer price,
		int quantity,
		boolean soldOut);

	default Map<String, Object> syncPriceAndStock(
		String marketItemId,
		Map<String, Object> currentRawData,
		Integer price,
		int quantity,
		boolean soldOut,
		Product product) {
		return syncPriceAndStock(marketItemId, currentRawData, price, quantity, soldOut);
	}

	Map<String, Object> syncImagesAndHtml(
		Product product,
		String marketItemId,
		Map<String, Object> currentRawData,
		List<String> hostedImages,
		String newDetailHtml);

	default void deleteFromMarket(String marketItemId) {
		throw new UnsupportedOperationException(
			getSupportedMarket() + " 삭제 API 미구현");
	}

	default Optional<String> fetchLinkIdentifier(String sourceIdentifier) {
		return Optional.empty();
	}

	default Map<String, String> fetchLinkIdentifiers(List<String> sourceIdentifiers) {
		Map<String, String> out = new HashMap<>();
		if (sourceIdentifiers == null) {
			return out;
		}
		for (String s : sourceIdentifiers) {
			fetchLinkIdentifier(s).ifPresent(v -> out.put(s, v));
		}
		return out;
	}

	default Map<String, String> fetchAllLinkIdentifiers(long throttleMs) {
		return null;
	}

	default Optional<String> removeSellerImmediateDiscount(String marketItemId, boolean dryRun) {
		return Optional.empty();
	}
}

package com.sbshop.agent.core.domain.market.client;

import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalResult;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPrice;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPriceMiss;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketLiveOption;
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

	default List<MarketCatalogEntry> fetchCatalog(long throttleMs) {
		return null;
	}

	default String catalogUnsupportedReason() {
		return null;
	}

	default boolean supportsSingleLookup() {
		return false;
	}

	default Optional<MarketCatalogEntry> fetchBySellerCode(String sellerCode) {
		return Optional.empty();
	}

	default Optional<String> removeSellerImmediateDiscount(String marketItemId, boolean dryRun) {
		return Optional.empty();
	}

	default boolean supportsLiveOptionLookup() {
		return false;
	}

	default Optional<MarketLiveOption> fetchLiveOption(String optionId) {
		return Optional.empty();
	}

	default MarketDraftPrice fetchDraftSalePrice(String marketItemId) {
		return MarketDraftPrice.missing(MarketDraftPriceMiss.UNSUPPORTED);
	}

	default boolean supportsApprovalRequest() {
		return false;
	}

	default MarketApprovalResult requestApproval(String marketItemId) {
		throw new UnsupportedOperationException(getSupportedMarket() + " 승인 요청 API 미지원");
	}
}

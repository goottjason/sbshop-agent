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

	default com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication preparePublication(Product product,
		java.math.BigDecimal price) {
		throw new UnsupportedOperationException("등록 요청을 고정하고 결과를 검증하는 계약 확인이 필요합니다.");
	}

	default Map<String, String> submitPreparedPublication(Product product, String operationId, String payload) {
		throw new UnsupportedOperationException("검토한 등록 요청의 전송 계약 확인이 필요합니다.");
	}

	default boolean verifyPreparedPublication(String listingId, String sbCode, String payload) {
		throw new UnsupportedOperationException("등록 결과 검증 계약 확인이 필요합니다.");
	}

	default com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead readSalePrice(String listingId,
		String optionId) {
		throw new UnsupportedOperationException("이 마켓의 가격 단독 수정·재조회 계약 확인이 필요합니다.");
	}

	/** Returning from this method is receipt only. Callers must read back the price. */
	default void writeSalePrice(String listingId, String optionId, java.math.BigDecimal price) {
		throw new UnsupportedOperationException("이 마켓의 가격 단독 수정·재조회 계약 확인이 필요합니다.");
	}

	default com.sbshop.agent.core.domain.market.client.dto.MarketStockRead readStockQuantity(String listingId,
		String optionId, String expectedSbCode) {
		throw new UnsupportedOperationException("이 마켓의 수량 단독 수정·재조회 계약 확인이 필요합니다.");
	}

	/** Revalidate exact identity/status, then invoke the durable intent guard immediately before PUT.
	 * A normal return is only a receipt; the caller must obtain separate quantity readback proof. */
	default void writeStockQuantity(String listingId, String optionId, String expectedSbCode, int quantity,
		String expectedAccountReference, Runnable beforeWrite) {
		throw new UnsupportedOperationException("이 마켓의 수량 단독 수정·재조회 계약 확인이 필요합니다.");
	}

	MarketType getSupportedMarket();

	default String inspectionAccountReference() {
		return null;
	}

	Map<String, String> publish(Product product);

	default Map<String, String> publish(Product product, MarketPublishContext context) {
		return publish(product);
	}

	MarketItemInfo extractMarketItem(String marketItemId);

	default com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation inspectListing(
		String marketItemId) {
		return com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation
			.unknown("이 마켓의 연결 해제 판정은 아직 검증되지 않았습니다.");
	}

	default com.sbshop.agent.core.domain.market.MarketPresence checkPresence(String marketItemId) {
		try {
			return extractMarketItem(marketItemId) == null
				? com.sbshop.agent.core.domain.market.MarketPresence.UNKNOWN
				: com.sbshop.agent.core.domain.market.MarketPresence.PRESENT;
		} catch (Exception e) {
			return com.sbshop.agent.core.domain.market.MarketFailureClassifier.indicatesDeleted(e)
				? com.sbshop.agent.core.domain.market.MarketPresence.ABSENT
				: com.sbshop.agent.core.domain.market.MarketPresence.UNKNOWN;
		}
	}

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

	default boolean syncBarcode(Product product, String marketItemId, Map<String, Object> currentRawData) {
		throw new UnsupportedOperationException(
			getSupportedMarket() + " 바코드 전송 미지원");
	}

	default Map<String, Object> syncProductFields(Product product, String marketItemId,
		Map<String, Object> currentRawData,
		java.util.Set<com.sbshop.agent.core.domain.market.client.dto.MarketEditField> fields) {
		throw new UnsupportedOperationException(
			getSupportedMarket() + " 필드 수정 미지원");
	}

	default boolean repairProductNotice(Product product, String marketItemId) {
		throw new UnsupportedOperationException(
			getSupportedMarket() + " 고시정보 보정 미지원");
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

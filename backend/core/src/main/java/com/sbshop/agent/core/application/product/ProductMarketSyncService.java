package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductMarketSyncService {
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;
	private final MarketSalePriceResolver marketSalePriceResolver;

	private final ProductReader productReader;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final int BLOCKED_RECHECK_DAYS = 7;

	public MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus) {
		return syncPriceStock(productId, price, stockStatus, true);
	}

	public MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus,
		boolean changed) {
		boolean soldOut = stockStatus == StockStatus.OUT_OF_STOCK;
		int quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY;

		return syncInternal(productId, marketType -> price, quantity, soldOut, changed);
	}

	public MarketRepublishResult syncPriceStockPerMarket(Long productId, PricingInputs pricing,
		StockStatus stockStatus, boolean changed) {
		boolean soldOut = stockStatus == StockStatus.OUT_OF_STOCK;
		int quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY;
		return syncInternal(productId, marketType -> priceForMarket(pricing, marketType), quantity, soldOut,
			changed);
	}

	private Integer priceForMarket(PricingInputs p, MarketType marketType) {
		if (!hasUsableCost(p)) {
			return null;
		}
		return marketSalePriceResolver.resolve(p, marketType);
	}

	/**
	 * 원가 0 은 "공짜"가 아니라 <b>품절이라 가격을 못 읽었거나 링크가 죽었다</b>는 뜻이다.
	 * 0 으로 계산하면 마진·수수료만 얹힌 쓰레기 값이 마켓에 나간다 — 2026-08-31 OCD 배치에서
	 * 원본 소멸 31건이 47,500~182,300원에서 11,000원대로 덮였다([[D-253]]).
	 * {@code null} 을 돌려주면 마켓 클라이언트가 가격 갱신을 건너뛰고 재고만 반영한다.
	 */
	static boolean hasUsableCost(PricingInputs p) {
		return p != null && p.buyPrice() != null && p.buyPrice().signum() > 0;
	}

	private MarketRepublishResult syncInternal(Long productId, Function<MarketType, Integer> priceResolver,
		int quantity, boolean soldOut, boolean changed) {
		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);

		Product product = productReader.findById(productId).orElse(null);
		List<MarketType> synced = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();

		for (MarketRegistration reg : registrations) {
			MarketType marketType = reg.getMarketType();

			if (marketType == MarketType.CAFE24 && !changed && Boolean.TRUE.equals(reg.getIsSynced())
				&& reg.getLastSyncError() == null) {
				skipped.add(marketType);
				log.info("[가격재고동기화] 변경없음 스킵(Cafe24): productId={}", productId);
				continue;
			}
			if (!marketClientRouter.hasClient(marketType)) {
				skipped.add(marketType);
				log.info("[가격재고동기화] 마켓 클라이언트 없음 — 스킵: productId={}, market={}", productId, marketType);
				continue;
			}
			String writeBlock = writeBlockedReason(reg);
			if (writeBlock != null) {
				skipped.add(marketType);
				log.info("[가격재고동기화] {} — 스킵: productId={}, market={}", writeBlock, productId, marketType);
				continue;
			}
			try {
				String marketItemId = reg.extractMarketCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
				Map<String, Object> currentRawData = mergeRawDataWithIdentifiers(objectMapper,
					reg.getMarketDetailedInfo(), reg.getMarketIdentifiers());

				MarketClient client = marketClientRouter.getClient(marketType);
				Map<String, Object> updated = client.syncPriceAndStock(marketItemId, currentRawData,
					priceResolver.apply(marketType),
					quantity, soldOut, product);

				if (updated != null) {
					reg.updateMarketDetailedInfo(objectMapper.writeValueAsString(updated));
				}
				reg.markSynced();
				marketRegistrationRepository.save(reg);
				synced.add(marketType);
				log.info("[가격재고동기화] 성공: productId={}, market={}, marketItemId={}", productId, marketType, marketItemId);
			} catch (Exception e) {
				boolean absent = MarketFailureClassifier.indicatesDeleted(e);
				if (absent) {
					reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
				} else {
					reg.recordSyncError(MarketFailureClassifier.classifyError(e), rootMessage(e));
				}
				marketRegistrationRepository.save(reg);
				failed.put(marketType, rootMessage(e));
				log.error("[가격재고동기화] 실패(부분 실패로 수집, 롤백하지 않음): productId={}, market={}, "
					+ "마켓부재={}, 쓰기오류={}, error={}",
					productId, marketType, absent, reg.getLastSyncError(), rootMessage(e), e);
			}
		}

		log.info("[가격재고동기화] 완료: productId={}, synced={}, skipped={}, failed={}",
			productId, synced, skipped, failed.keySet());
		return new MarketRepublishResult(synced, skipped, failed);
	}

	/**
	 * 마켓 클라이언트가 쓰는 rawData 에 식별자를 함께 싣는다.
	 * 쿠팡 단계 가격조정([[D-246]])은 {@code sellerProductId} 로 현재가를 읽는데,
	 * {@code marketDetailedInfo} 에는 운영 1,262건 중 20건에만 있고 {@code marketIdentifiers} 에는 전부 있다.
	 * 상세정보 값이 우선한다 — 마켓에서 읽어온 최신값을 식별자로 덮지 않는다.
	 */
	static Map<String, Object> mergeRawDataWithIdentifiers(ObjectMapper objectMapper,
		String detailedInfo, String identifiers) {
		Map<String, Object> merged = new HashMap<>(parseJsonMap(objectMapper, identifiers));
		merged.putAll(parseJsonMap(objectMapper, detailedInfo));
		return merged;
	}

	private static Map<String, Object> parseJsonMap(ObjectMapper objectMapper, String json) {
		if (json == null || json.isBlank()) {
			return new HashMap<>();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			return new HashMap<>();
		}
	}

	private Map<String, Object> parseRawData(String json) {
		if (json == null || json.isBlank()) {
			return new HashMap<>();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			log.warn("[가격재고동기화] marketDetailedInfo 파싱 실패 — 빈 rawData로 진행: {}", e.getMessage());
			return new HashMap<>();
		}
	}

	private static String writeBlockedReason(MarketRegistration reg) {
		if (reg.getUnsyncReason() == UnsyncReason.DELETED_ON_MARKET) {
			return "마켓에서 삭제된 상품";
		}
		if (reg.getLastSyncError() != SyncErrorType.BLOCKED_BY_MARKET) {
			return null;
		}
		LocalDateTime blockedAt = reg.getLastSyncErrorAt();
		if (blockedAt == null || blockedAt.isBefore(LocalDateTime.now().minusDays(BLOCKED_RECHECK_DAYS))) {
			return null;
		}
		return "마켓이 막아둔 상품(재확인까지 " + BLOCKED_RECHECK_DAYS + "일)";
	}

	private String rootMessage(Throwable e) {
		Throwable cur = e;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		String msg = cur.getMessage();
		return msg != null ? sanitizeMarketMessage(msg) : cur.getClass().getSimpleName();
	}

	private static final Pattern HTML_TAG_PATTERN = Pattern.compile("</?[a-zA-Z][^>]*>");
	private static final int MAX_SYNC_ERROR_LENGTH = 480;

	static String sanitizeMarketMessage(String message) {
		if (message == null) {
			return null;
		}
		String noTags = HTML_TAG_PATTERN.matcher(message).replaceAll("");
		String noEntities = noTags
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.replace("&nbsp;", " ")
			.replace("&amp;", "&");
		String collapsed = noEntities.replaceAll("\\s+", " ").trim();
		if (collapsed.length() > MAX_SYNC_ERROR_LENGTH) {
			return collapsed.substring(0, MAX_SYNC_ERROR_LENGTH) + "…";
		}
		return collapsed;
	}
}

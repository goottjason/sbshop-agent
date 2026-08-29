package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
		return marketSalePriceResolver.resolve(p, marketType);
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

			if (marketType == MarketType.CAFE24 && !changed && Boolean.TRUE.equals(reg.getIsSynced())) {
				skipped.add(marketType);
				log.info("[가격재고동기화] 변경없음 스킵(Cafe24): productId={}", productId);
				continue;
			}
			if (!marketClientRouter.hasClient(marketType)) {
				skipped.add(marketType);
				log.info("[가격재고동기화] 마켓 클라이언트 없음 — 스킵: productId={}, market={}", productId, marketType);
				continue;
			}
			try {
				String marketItemId = reg.extractMarketCode();
				if (marketItemId == null || marketItemId.isEmpty()) {
					throw new IllegalStateException("마켓 상품코드 없음(연동정보에 코드 키 부재)");
				}
				Map<String, Object> currentRawData = parseRawData(reg.getMarketDetailedInfo());

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
					reg.recordSyncError(MarketFailureClassifier.classifyError(e));
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

	private String rootMessage(Throwable e) {
		Throwable cur = e;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		String msg = cur.getMessage();
		return msg != null ? msg : cur.getClass().getSimpleName();
	}
}

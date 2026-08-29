package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductBarcodeSyncUseCase {

	private final ProductRepository productRepository;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;
	private final ObjectMapper objectMapper;

	public record MarketOutcome(MarketType market, String result, String detail) {
	}

	public record ProductOutcome(Long productId, String sbCode, String barcode,
		List<MarketOutcome> markets) {
	}

	public List<ProductOutcome> sync(List<Long> productIds, boolean dryRun) {
		List<ProductOutcome> outcomes = new ArrayList<>();
		for (Long productId : productIds) {
			Product product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				outcomes.add(new ProductOutcome(productId, null, null,
					List.of(new MarketOutcome(null, "SKIPPED", "상품 없음"))));
				continue;
			}
			String barcode = product.getProductSpec() == null ? null : product.getProductSpec().getBarcode();
			if (barcode == null || barcode.isBlank()) {
				outcomes.add(new ProductOutcome(productId, product.getSbCode(), null,
					List.of(new MarketOutcome(null, "SKIPPED", "바코드 없음"))));
				continue;
			}
			outcomes.add(new ProductOutcome(productId, product.getSbCode(), barcode,
				syncOne(product, productId, dryRun)));
		}
		return outcomes;
	}

	private List<MarketOutcome> syncOne(Product product, Long productId, boolean dryRun) {
		List<MarketOutcome> results = new ArrayList<>();
		for (MarketRegistration reg : marketRegistrationRepository.findByProductId(productId)) {
			MarketType market = reg.getMarketType();
			if (!marketClientRouter.hasClient(market)) {
				results.add(new MarketOutcome(market, "SKIPPED", "클라이언트 없음"));
				continue;
			}
			if (!Boolean.TRUE.equals(reg.getIsSynced())) {
				results.add(new MarketOutcome(market, "SKIPPED", "미동기 등록 — 마켓에 없거나 반영 안 됨"));
				continue;
			}
			String marketItemId = reg.extractDeleteCode();
			if (marketItemId == null || marketItemId.isEmpty()) {
				results.add(new MarketOutcome(market, "SKIPPED", "마켓 상품코드 없음"));
				continue;
			}
			if (dryRun) {
				results.add(new MarketOutcome(market, "DRY_RUN", marketItemId));
				continue;
			}
			try {
				results.add(push(product, reg, market, marketItemId));
			} catch (Exception e) {
				boolean absent = MarketFailureClassifier.indicatesDeleted(e);
				if (absent) {
					reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
				} else {
					reg.recordSyncError(MarketFailureClassifier.classifyError(e));
				}
				marketRegistrationRepository.save(reg);
				log.error("[바코드전송] 실패: productId={}, market={}, 마켓부재={}, 쓰기오류={}, error={}",
					productId, market, absent, reg.getLastSyncError(), e.getMessage(), e);
				results.add(new MarketOutcome(market, "FAILED", e.getMessage()));
			}
		}
		return results;
	}

	private MarketOutcome push(Product product, MarketRegistration reg, MarketType market,
		String marketItemId) throws Exception {
		MarketClient client = marketClientRouter.getClient(market);
		Map<String, Object> rawData = parseRawData(reg.getMarketDetailedInfo());
		try {
			client.syncBarcode(product, marketItemId, rawData);
		} catch (UnsupportedOperationException unsupported) {
			return new MarketOutcome(market, "UNSUPPORTED", unsupported.getMessage());
		}
		reg.updateMarketDetailedInfo(objectMapper.writeValueAsString(rawData));
		reg.enrichIdentifier("barcode", product.getProductSpec() == null
			? null : product.getProductSpec().getBarcode());
		reg.markSynced();
		marketRegistrationRepository.save(reg);
		return new MarketOutcome(market, "SENT", marketItemId);
	}

	private Map<String, Object> parseRawData(String json) {
		if (json == null || json.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			return new LinkedHashMap<>();
		}
	}
}

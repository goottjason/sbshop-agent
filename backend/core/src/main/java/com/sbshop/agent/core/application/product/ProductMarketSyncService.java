package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * D-060: 상품의 연동 마켓 목록을 순회하며 가격/재고를 각 마켓에 반영한다(단건·배치 공용).
 * 규율: 클라이언트 없는 마켓(GMARKET/AUCTION) 스킵, 마켓별 try로 부분 실패 수집(한 마켓 실패가 나머지를 롤백하지 않음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductMarketSyncService {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus) {
		boolean soldOut = stockStatus == StockStatus.OUT_OF_STOCK;
		int quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY;
		return syncInternal(productId, price, quantity, soldOut);
	}

	/** 임시 호환 오버로드(기존 caller 유지용). stock<=0 → 품절. Task 4에서 caller 이관 후 제거. */
	public MarketRepublishResult syncPriceStock(Long productId, Integer price, Integer stock) {
		StockStatus status = (stock == null || stock <= 0) ? StockStatus.OUT_OF_STOCK : StockStatus.IN_STOCK;
		return syncPriceStock(productId, price, status);
	}

	private MarketRepublishResult syncInternal(Long productId, Integer price, int quantity, boolean soldOut) {
		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		List<MarketType> synced = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();

		for (MarketRegistration reg : registrations) {
			MarketType marketType = reg.getMarketType();
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
				Map<String, Object> updated =
					client.syncPriceAndStock(marketItemId, currentRawData, price, quantity, soldOut);

				if (updated != null) {
					reg.updateMarketDetailedInfo(objectMapper.writeValueAsString(updated));
				}
				reg.markSynced();
				marketRegistrationRepository.save(reg);
				synced.add(marketType);
				log.info("[가격재고동기화] 성공: productId={}, market={}, marketItemId={}", productId, marketType, marketItemId);
			} catch (Exception e) {
				failed.put(marketType, rootMessage(e));
				log.error("[가격재고동기화] 실패(부분 실패로 수집, 롤백하지 않음): productId={}, market={}, error={}",
					productId, marketType, rootMessage(e), e);
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
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception e) {
			log.warn("[가격재고동기화] marketDetailedInfo 파싱 실패 — 빈 rawData로 진행: {}", e.getMessage());
			return new HashMap<>();
		}
	}

	/** 예외 체인의 가장 안쪽 메시지(래핑된 실 HTTP 오류)를 표면화용으로 추출. */
	private String rootMessage(Throwable e) {
		Throwable cur = e;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		String msg = cur.getMessage();
		return msg != null ? msg : cur.getClass().getSimpleName();
	}
}

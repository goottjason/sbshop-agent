package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
	private final MarginCalculator marginCalculator;
	private final MarketFeeService marketFeeService;
	// 스마트스토어 단위가격(가격표시제) 등 상품 속성 전달용 — 마켓 전송 시 Product 조회.
	private final ProductReader productReader;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus) {
		// changed=true: 변경 여부 정보가 없는 호출자(단건 수정 등)는 항상 전송(현행 동작 보존).
		return syncPriceStock(productId, price, stockStatus, true);
	}

	/**
	 * changed=false이면 Cafe24 등록행 중 직전 동기화가 성공(isSynced=true)한 것은 재전송을 스킵한다.
	 * (변경 없는 상품의 불필요한 Cafe24 재전송 → downstream 옥션 연동 노이즈를 줄인다. 우선 Cafe24만.)
	 */
	public MarketRepublishResult syncPriceStock(Long productId, Integer price, StockStatus stockStatus,
		boolean changed) {
		boolean soldOut = stockStatus == StockStatus.OUT_OF_STOCK;
		int quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY;
		// 단일 가격 경로(단건 수정 등): 모든 마켓에 같은 price를 전송(현행 동작 보존).
		return syncInternal(productId, marketType -> price, quantity, soldOut, changed);
	}

	/**
	 * D-094: 마켓별 실수수료(sb_fee_policy)로 판매가를 <b>따로 산정</b>해 각 마켓에 전송한다.
	 * 같은 원가·마진·쿠폰·최소마진이라도 쿠팡(11%)·스토어(8%)·G마켓/11번가(18%)의 수수료 차이만큼
	 * 전송 가격이 달라져, 모든 마켓에서 목표 마진에 맞는 경쟁력 있는 가격을 유지한다.
	 */
	public MarketRepublishResult syncPriceStockPerMarket(Long productId, PricingInputs pricing,
		StockStatus stockStatus, boolean changed) {
		boolean soldOut = stockStatus == StockStatus.OUT_OF_STOCK;
		int quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY;
		return syncInternal(productId, marketType -> priceForMarket(pricing, marketType), quantity, soldOut,
			changed);
	}

	/** 마켓 실수수료로 산정한 그 마켓의 판매가(원, 정수). */
	private Integer priceForMarket(PricingInputs p, MarketType marketType) {
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(),
			p.couponRate(), p.minMarginPrice(), fee).intValue();
	}

	private MarketRepublishResult syncInternal(Long productId, Function<MarketType, Integer> priceResolver,
		int quantity, boolean soldOut, boolean changed) {
		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		// 스토어 단위가격 등 상품 속성 전달용(best-effort — 없으면 null로 기존 동작).
		Product product = productReader.findById(productId).orElse(null);
		List<MarketType> synced = new ArrayList<>();
		List<MarketType> skipped = new ArrayList<>();
		Map<MarketType, String> failed = new LinkedHashMap<>();

		for (MarketRegistration reg : registrations) {
			MarketType marketType = reg.getMarketType();
			// Cafe24 변경감지 스킵: 변경 없음 + 직전 동기화 성공이면 재전송하지 않는다(Cafe24 한정).
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
				Map<String, Object> updated =
					client.syncPriceAndStock(marketItemId, currentRawData, priceResolver.apply(marketType),
							quantity, soldOut, product);

				if (updated != null) {
					reg.updateMarketDetailedInfo(objectMapper.writeValueAsString(updated));
				}
				reg.markSynced();
				marketRegistrationRepository.save(reg);
				synced.add(marketType);
				log.info("[가격재고동기화] 성공: productId={}, market={}, marketItemId={}", productId, marketType, marketItemId);
			} catch (Exception e) {
				// 실패 표시를 영속화(isSynced=false) → 변경없음이어도 다음 배치에서 재시도된다.
				reg.markSyncFailed();
				marketRegistrationRepository.save(reg);
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

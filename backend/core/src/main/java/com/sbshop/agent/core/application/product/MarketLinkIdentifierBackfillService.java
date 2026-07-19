package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 상품 그리드 마켓 링크에 필요한 부가 식별자를 마켓 API로 조회해 market_identifiers에 백필한다.
 * <ul>
 *   <li>쿠팡: sellerProductId → productId (상품페이지 링크용)</li>
 *   <li>스토어: originProductNo → channelProductNo (상품페이지 링크용)</li>
 * </ul>
 * 대상 키가 이미 있으면 스킵(멱등). 조회 실패 건은 다음 실행에서 재시도된다(best-effort).
 * G마켓/옥션은 ESM 엑셀로 Cafe24 등록행에 이미 백필됨(이 서비스 범위 밖).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLinkIdentifierBackfillService {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	/** 마켓별: (조회 소스 키, 백필 대상 키, 요청 간 지연ms — 마켓 rate limit 회피). */
	private record Spec(String sourceKey, String targetKey, long throttleMs) {}

	private static final Map<MarketType, Spec> SPECS = Map.of(
		// 쿠팡은 여유 있음. 스토어는 초당 제한이 빡빡해 429가 잦으므로 요청 간 300ms 지연.
		MarketType.COUPANG, new Spec("sellerProductId", "productId", 100L),
		MarketType.SMART_STORE, new Spec("originProductNo", "channelProductNo", 300L));

	/**
	 * 전 마켓 백필 1회 실행. 마켓별 처리 결과 요약을 반환한다.
	 * @param limit 마켓별 최대 처리 건수(0 이하=무제한). 대량 호출 시 rate limit 조절용.
	 */
	public Map<String, Object> backfillAll(int limit) {
		Map<String, Object> summary = new LinkedHashMap<>();
		for (Map.Entry<MarketType, Spec> e : SPECS.entrySet()) {
			summary.put(e.getKey().name(), backfillMarket(e.getKey(), e.getValue(), limit));
		}
		return summary;
	}

	/**
	 * 백그라운드 실행 진입점. 수천 건 × 마켓 API 지연으로 수분~십수분 걸리므로 호출 스레드를 막지 않는다.
	 * @param onDone 완료 시 running 플래그 해제 등 후처리 콜백(실패해도 반드시 호출).
	 */
	@Async
	public void backfillAllAsync(int limit, Runnable onDone) {
		try {
			Map<String, Object> result = backfillAll(limit);
			log.info("[링크식별자 백필] 백그라운드 완료: {}", result);
		} catch (Exception e) {
			log.error("[링크식별자 백필] 백그라운드 실패: {}", e.getMessage(), e);
		} finally {
			if (onDone != null) {
				onDone.run();
			}
		}
	}

	private Map<String, Object> backfillMarket(MarketType marketType, Spec spec, int limit) {
		int scanned = 0;
		int skipped = 0;
		int updated = 0;
		int failed = 0;

		if (!marketClientRouter.hasClient(marketType)) {
			return resultMap(scanned, skipped, updated, failed, "no client");
		}
		MarketClient client = marketClientRouter.getClient(marketType);
		List<MarketRegistration> regs = marketRegistrationRepository.findByMarketType(marketType);

		for (MarketRegistration reg : regs) {
			scanned++;
			// 이미 대상 키 보유 → 스킵(멱등)
			if (reg.identifier(spec.targetKey()) != null) {
				skipped++;
				continue;
			}
			String source = reg.identifier(spec.sourceKey());
			if (source == null) {
				skipped++;
				continue;
			}
			try {
				Optional<String> value = client.fetchLinkIdentifier(source);
				if (value.isPresent()) {
					reg.enrichIdentifier(spec.targetKey(), value.get());
					marketRegistrationRepository.save(reg);
					updated++;
				} else {
					failed++;
				}
			} catch (Exception ex) {
				failed++;
				log.warn("[링크식별자 백필] {} productId={} 조회 실패: {}",
					marketType, reg.getProductId(), ex.getMessage());
			}
			// 마켓 rate limit 회피: 실제 조회를 시도한 건들 사이에만 지연.
			if (spec.throttleMs() > 0) {
				try {
					Thread.sleep(spec.throttleMs());
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			// limit: 마켓별 갱신 상한. 한 번에 다 채우지 않고 나눠 실행할 때 사용.
			if (limit > 0 && updated >= limit) {
				break;
			}
		}
		log.info("[링크식별자 백필] {} 완료 — 스캔 {}, 스킵 {}, 갱신 {}, 실패 {}",
			marketType, scanned, skipped, updated, failed);
		return resultMap(scanned, skipped, updated, failed, "ok");
	}

	private Map<String, Object> resultMap(int scanned, int skipped, int updated, int failed, String status) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("status", status);
		m.put("scanned", scanned);
		m.put("skipped", skipped);
		m.put("updated", updated);
		m.put("failed", failed);
		return m;
	}
}

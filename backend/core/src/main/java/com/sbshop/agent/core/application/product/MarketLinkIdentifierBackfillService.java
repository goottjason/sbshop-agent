package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/** 마켓별: (조회 소스 키, 백필 대상 키, 배치 크기, 배치 간 지연ms). */
	private record Spec(String sourceKey, String targetKey, int batchSize, long throttleMs) {}

	private static final Map<MarketType, Spec> SPECS = Map.of(
		// 쿠팡: seller-products GET은 단건 API(배치 불가) → batch=1, 요청 간 100ms.
		MarketType.COUPANG, new Spec("sellerProductId", "productId", 1, 100L),
		// 스토어: 상품검색 API가 originProductNos 배열을 받음 → 100건 배치로 요청 수 100배 감소(429 회피).
		//         배치 간 500ms.
		MarketType.SMART_STORE, new Spec("originProductNo", "channelProductNo", 100, 500L));

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

		// 처리 대상만 수집: 대상 키 미보유 + 소스 키 보유. (source → registration 매핑)
		Map<String, MarketRegistration> pending = new LinkedHashMap<>();
		for (MarketRegistration reg : regs) {
			scanned++;
			if (reg.identifier(spec.targetKey()) != null) {
				skipped++;
				continue;
			}
			String source = reg.identifier(spec.sourceKey());
			if (source == null) {
				skipped++;
				continue;
			}
			pending.put(source, reg);
		}

		List<String> sources = new java.util.ArrayList<>(pending.keySet());
		outer:
		for (int i = 0; i < sources.size(); i += spec.batchSize()) {
			List<String> chunk = sources.subList(i, Math.min(i + spec.batchSize(), sources.size()));
			try {
				Map<String, String> found = client.fetchLinkIdentifiers(chunk);
				for (String source : chunk) {
					String value = found.get(source);
					if (value != null) {
						MarketRegistration reg = pending.get(source);
						reg.enrichIdentifier(spec.targetKey(), value);
						marketRegistrationRepository.save(reg);
						updated++;
					} else {
						failed++;
					}
					if (limit > 0 && updated >= limit) {
						break outer;
					}
				}
			} catch (Exception ex) {
				failed += chunk.size();
				log.warn("[링크식별자 백필] {} 배치 조회 실패 ({}건): {}", marketType, chunk.size(), ex.getMessage());
			}
			// 배치 간 지연으로 rate limit 회피.
			if (spec.throttleMs() > 0 && i + spec.batchSize() < sources.size()) {
				try {
					Thread.sleep(spec.throttleMs());
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
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

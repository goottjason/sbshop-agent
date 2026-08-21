package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLinkIdentifierBackfillService {
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	private record Spec(String sourceKey, String targetKey, int batchSize, long throttleMs, boolean bulkScan) {
	}

	private static final Map<MarketType, Spec> SPECS = Map.of(

		MarketType.COUPANG, new Spec("sellerProductId", "productId", 1, 100L, false),

		MarketType.SMART_STORE, new Spec("originProductNo", "channelProductNo", 100, 500L, true));

	public Map<String, Object> backfillAll(int limit) {
		Map<String, Object> summary = new LinkedHashMap<>();
		for (Map.Entry<MarketType, Spec> e : SPECS.entrySet()) {
			summary.put(e.getKey().name(), backfillMarket(e.getKey(), e.getValue(), limit));
		}
		return summary;
	}

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

		List<String> sources = new ArrayList<>(pending.keySet());

		if (spec.bulkScan()) {
			Map<String, String> all = client.fetchAllLinkIdentifiers(spec.throttleMs());
			if (all == null) {
				all = Map.of();
			}
			for (Map.Entry<String, MarketRegistration> e : pending.entrySet()) {
				String value = all.get(e.getKey());
				if (value != null) {
					e.getValue().enrichIdentifier(spec.targetKey(), value);
					marketRegistrationRepository.save(e.getValue());
					updated++;
					if (limit > 0 && updated >= limit) {
						break;
					}
				} else {
					failed++;
				}
			}
			log.info("[링크식별자 백필] {} 완료(전체스캔) — 스캔 {}, 스킵 {}, 갱신 {}, 실패 {}",
				marketType, scanned, skipped, updated, failed);
			return resultMap(scanned, skipped, updated, failed, "ok");
		}

		outer: for (int i = 0; i < sources.size(); i += spec.batchSize()) {
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

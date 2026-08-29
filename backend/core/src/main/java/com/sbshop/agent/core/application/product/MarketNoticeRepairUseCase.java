package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
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
public class MarketNoticeRepairUseCase {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ProductRepository productRepository;
	private final MarketClientRouter marketClientRouter;

	public record Report(int examined, int repaired, int alreadyOk, int skipped, int failed,
		Map<String, Integer> failureReasons, List<String> repairedIds) {
	}

	public Report repair(MarketType market, int limit, long throttleMs, boolean dryRun,
		boolean syncedOnly) {
		return repair(market, limit, throttleMs, dryRun, syncedOnly, List.of());
	}

	public Report repair(MarketType market, int limit, long throttleMs, boolean dryRun,
		boolean syncedOnly, List<String> marketItemIds) {
		List<MarketRegistration> registrations = syncedOnly
			? marketRegistrationRepository.findByMarketTypeAndIsSyncedTrue(market)
			: marketRegistrationRepository.findByMarketType(market);
		if (marketItemIds != null && !marketItemIds.isEmpty()) {
			registrations = registrations.stream()
				.filter(r -> marketItemIds.contains(r.extractDeleteCode()))
				.toList();
		}
		int examined = 0;
		int repaired = 0;
		int alreadyOk = 0;
		int skipped = 0;
		int failed = 0;
		Map<String, Integer> reasons = new LinkedHashMap<>();
		List<String> repairedIds = new ArrayList<>();

		if (!marketClientRouter.hasClient(market)) {
			return new Report(0, 0, 0, registrations.size(), 0,
				Map.of("클라이언트 없음", registrations.size()), List.of());
		}
		MarketClient client = marketClientRouter.getClient(market);

		for (MarketRegistration reg : registrations) {
			if (limit > 0 && examined >= limit) {
				break;
			}
			String marketItemId = reg.extractDeleteCode();
			if (marketItemId == null || marketItemId.isEmpty()) {
				skipped++;
				continue;
			}
			examined++;
			if (dryRun) {
				continue;
			}
			try {
				Product product = productRepository.findById(reg.getProductId()).orElse(null);
				if (client.repairProductNotice(product, marketItemId)) {
					repaired++;
					repairedIds.add(marketItemId);
				} else {
					alreadyOk++;
				}
			} catch (Exception e) {
				failed++;
				String reason = shortReason(e);
				reasons.merge(reason, 1, Integer::sum);
				log.warn("[고시정보보정] 실패: market={} id={} reason={}", market, marketItemId, reason);
			}
			sleepQuietly(throttleMs);
			if (examined % 100 == 0) {
				log.info("[고시정보보정] 진행 {}건 — 보정 {} / 정상 {} / 실패 {}",
					examined, repaired, alreadyOk, failed);
			}
		}
		log.info("[고시정보보정] 완료: 조사 {} / 보정 {} / 정상 {} / 스킵 {} / 실패 {}",
			examined, repaired, alreadyOk, skipped, failed);
		return new Report(examined, repaired, alreadyOk, skipped, failed, reasons, repairedIds);
	}

	private static String shortReason(Exception e) {
		String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return msg.length() > 120 ? msg.substring(0, 120) : msg;
	}

	private static void sleepQuietly(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

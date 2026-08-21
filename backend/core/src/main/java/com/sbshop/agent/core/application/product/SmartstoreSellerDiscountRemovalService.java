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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartstoreSellerDiscountRemovalService {
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	private long throttleMs = 500L;

	private static final int MAX_ATTEMPTS = 3;

	private long retryBackoffMs = 2000L;

	public void setThrottleMs(long throttleMs) {
		this.throttleMs = throttleMs;
	}

	public void setRetryBackoffMs(long retryBackoffMs) {
		this.retryBackoffMs = retryBackoffMs;
	}

	public Map<String, Object> removeForProducts(List<Long> productIds, boolean dryRun) {
		List<MarketRegistration> regs = marketRegistrationRepository.findByProductIdIn(productIds).stream()
			.filter(r -> r.getMarketType() == MarketType.SMART_STORE)
			.toList();
		return process(regs, dryRun);
	}

	@Async
	public void removeAllAsync(boolean dryRun, Runnable onDone) {
		try {
			List<MarketRegistration> regs = marketRegistrationRepository.findByMarketType(MarketType.SMART_STORE);
			log.info("[스토어즉시할인제거] 전체 실행 시작 (dryRun={}, 대상={}건)", dryRun, regs.size());
			Map<String, Object> summary = process(regs, dryRun);
			log.info("[스토어즉시할인제거] 전체 실행 완료: {}", summary);
		} finally {
			if (onDone != null) {
				onDone.run();
			}
		}
	}

	private Map<String, Object> process(List<MarketRegistration> regs, boolean dryRun) {
		int removed = 0;
		int skipped = 0;
		int failed = 0;
		List<String> removedDetails = new ArrayList<>();
		Map<String, String> failures = new LinkedHashMap<>();

		if (!marketClientRouter.hasClient(MarketType.SMART_STORE)) {
			log.warn("[스토어즉시할인제거] 스토어 클라이언트 없음 — 중단");
			return summary(regs.size(), 0, 0, 0, removedDetails, failures);
		}
		MarketClient client = marketClientRouter.getClient(MarketType.SMART_STORE);

		for (MarketRegistration reg : regs) {
			String code = reg.extractMarketCode();
			if (code == null || code.isEmpty()) {
				failed++;
				failures.put("product:" + reg.getProductId(), "originProductNo 없음");
				continue;
			}
			try {
				Optional<String> res = removeWithRetry(client, code, dryRun);
				if (res.isPresent()) {
					removed++;
					removedDetails.add(code + "=" + res.get());
				} else {
					skipped++;
				}
				Thread.sleep(throttleMs);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				failed++;
				failures.put(code, "중단됨");
				break;
			} catch (Exception e) {
				failed++;
				failures.put(code, e.getMessage());
			}
		}
		return summary(regs.size(), removed, skipped, failed, removedDetails, failures);
	}

	private Optional<String> removeWithRetry(MarketClient client, String code, boolean dryRun)
		throws InterruptedException {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return client.removeSellerImmediateDiscount(code, dryRun);
			} catch (RuntimeException e) {
				last = e;
				if (attempt < MAX_ATTEMPTS) {
					long backoff = retryBackoffMs * attempt;
					log.warn("[스토어즉시할인제거] {} 실패(시도 {}/{}) — {}ms 후 재시도: {}",
						code, attempt, MAX_ATTEMPTS, backoff, e.getMessage());
					Thread.sleep(backoff);
				}
			}
		}
		throw last;
	}

	private Map<String, Object> summary(int total, int removed, int skipped, int failed,
		List<String> removedDetails, Map<String, String> failures) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("total", total);
		m.put("removed", removed);
		m.put("skipped", skipped);
		m.put("failed", failed);
		m.put("details", removedDetails);
		m.put("failures", failures);
		return m;
	}
}

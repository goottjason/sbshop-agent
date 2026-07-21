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

/**
 * D-096: 스마트스토어 판매자 즉시할인(customerBenefit.immediateDiscountPolicy) 일괄 제거(일회성).
 * 마켓별 가격이 이미 스토어 저수수료(8%)에 맞게 낮게 산정되므로, 상품에 별도로 걸린 즉시할인이
 * 겹치면 이중할인으로 손해가 난다. 이를 제거한다(멱등: 할인이 있을 때만 제거).
 *
 * <p>소규모 검증은 {@link #removeForProducts}(동기, productIds 지정), 전체 실행은
 * {@link #removeAllAsync}(비동기 — 스토어 상품 수천 건 × API 지연).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartstoreSellerDiscountRemovalService {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	/** 네이버 API rate limit 회피용 항목 간 지연(ms). */
	private static final long THROTTLE_MS = 200L;

	/** 소규모 동기 실행(검증용): 지정 상품의 스토어 등록행만 처리. */
	public Map<String, Object> removeForProducts(List<Long> productIds, boolean dryRun) {
		List<MarketRegistration> regs = marketRegistrationRepository.findByProductIdIn(productIds).stream()
			.filter(r -> r.getMarketType() == MarketType.SMART_STORE)
			.toList();
		return process(regs, dryRun);
	}

	/** 전체 스토어 상품 비동기 실행(수천 건 × API 지연 → 백그라운드). */
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
				Optional<String> res = client.removeSellerImmediateDiscount(code, dryRun);
				if (res.isPresent()) {
					removed++;
					removedDetails.add(code + "=" + res.get());
				} else {
					skipped++;
				}
				Thread.sleep(THROTTLE_MS);
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

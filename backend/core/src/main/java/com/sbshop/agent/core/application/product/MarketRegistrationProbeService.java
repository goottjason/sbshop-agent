package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.UnsyncReasonClassifier;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRegistrationProbeService {

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	public record ProbeOutcome(Long productId, MarketType market, String marketItemId, String result,
		String detail) {
	}

	public List<ProbeOutcome> probe(MarketType marketType, int limit, long throttleMs, boolean dryRun) {
		List<ProbeOutcome> outcomes = new ArrayList<>();
		if (!marketClientRouter.hasClient(marketType)) {
			return outcomes;
		}
		MarketClient client = marketClientRouter.getClient(marketType);
		int probed = 0;
		for (MarketRegistration reg : marketRegistrationRepository.findUnclassifiedUnsynced(marketType)) {
			if (probed >= limit) {
				break;
			}
			String marketItemId = reg.extractLiveLookupId();
			if (marketItemId == null) {
				continue;
			}
			probed++;
			if (dryRun) {
				outcomes.add(new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "DRY_RUN", null));
				continue;
			}
			outcomes.add(probeOne(client, reg, marketType, marketItemId));
			sleepQuietly(throttleMs);
		}
		return outcomes;
	}

	private ProbeOutcome probeOne(MarketClient client, MarketRegistration reg, MarketType marketType,
		String marketItemId) {
		try {
			client.extractMarketItem(marketItemId);
			return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "ALIVE", null);
		} catch (Exception e) {
			UnsyncReason reason = UnsyncReasonClassifier.classify(e);
			if (reason != UnsyncReason.DELETED_ON_MARKET) {
				return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "INCONCLUSIVE",
					rootMessage(e));
			}
			reg.markSyncFailed(UnsyncReason.DELETED_ON_MARKET);
			marketRegistrationRepository.save(reg);
			log.info("[등록프로브] 마켓에서 삭제 확인: productId={}, market={}, marketItemId={}",
				reg.getProductId(), marketType, marketItemId);
			return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "DELETED", rootMessage(e));
		}
	}

	private static String rootMessage(Throwable e) {
		Throwable current = e;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage();
	}

	private static void sleepQuietly(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}
}

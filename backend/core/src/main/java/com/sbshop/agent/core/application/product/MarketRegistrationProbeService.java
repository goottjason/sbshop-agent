package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketPresence;
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
		return probe(marketType, limit, throttleMs, dryRun, false);
	}

	public List<ProbeOutcome> probe(MarketType marketType, int limit, long throttleMs, boolean dryRun,
		boolean promoteAlive) {
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
			outcomes.add(probeOne(client, reg, marketType, marketItemId, promoteAlive));
			sleepQuietly(throttleMs);
		}
		return outcomes;
	}

	private ProbeOutcome probeOne(MarketClient client, MarketRegistration reg, MarketType marketType,
		String marketItemId, boolean promoteAlive) {
		try {
			MarketPresence presence = client.checkPresence(marketItemId);
			if (presence == MarketPresence.UNKNOWN) {
				return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "INCONCLUSIVE",
					"마켓이 존재 여부를 확정해 주지 않았다");
			}
			if (presence == MarketPresence.ABSENT) {
				return recordAbsent(reg, marketType, marketItemId, "마켓이 부재를 알렸다");
			}
			if (promoteAlive && !Boolean.TRUE.equals(reg.getIsSynced())) {
				reg.confirmPresentOnMarket();
				marketRegistrationRepository.save(reg);
				log.info("[등록프로브] 마켓 존재 확인 — 동기 상태로 승격: productId={}, market={}, marketItemId={}",
					reg.getProductId(), marketType, marketItemId);
				return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "PROMOTED", null);
			}
			return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "ALIVE", null);
		} catch (Exception e) {
			if (!MarketFailureClassifier.indicatesDeleted(e)) {
				return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "INCONCLUSIVE",
					rootMessage(e));
			}
			return recordAbsent(reg, marketType, marketItemId, rootMessage(e));
		}
	}

	private ProbeOutcome recordAbsent(MarketRegistration reg, MarketType marketType, String marketItemId,
		String detail) {
		if (reg.getUnsyncReason() != UnsyncReason.DELETED_ON_MARKET) {
			reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
			marketRegistrationRepository.save(reg);
			log.info("[등록프로브] 마켓에서 삭제 확인: productId={}, market={}, marketItemId={}",
				reg.getProductId(), marketType, marketItemId);
		}
		return new ProbeOutcome(reg.getProductId(), marketType, marketItemId, "DELETED", detail);
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

package com.sbshop.agent.core.application.actionlog;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.sync.SyncFreshnessPolicy;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ActionLogSyncListener {

	private final ActionLogService actionLogService;
	private final SyncStatusService syncStatusService;
	private final OrderRepository orderRepository;

	@EventListener
	public void onSyncCompleted(SyncCompletedEvent event) {
		String marketType = event.getMarketType().name();
		String actionType = marketType + "_SYNC";

		if (!event.isSuccess()) {
			String reason = event.getErrorMessage() != null ? event.getErrorMessage() : "원인 미상";
			actionLogService.record(actionType, marketType, ActionStatus.FAILED,
				"동기화 실패: " + reason);
			return;
		}

		actionLogService.record(actionType, marketType, ActionStatus.SUCCESS, successMessage(event));

		if (!event.isMeasured() || event.getNewCount() > 0) {
			return;
		}
		warnIfStale(event.getMarketType(), marketType, actionType);
	}

	private String successMessage(SyncCompletedEvent event) {
		if (!event.isMeasured()) {
			return "동기화 성공";
		}
		return "동기화 성공 — 처리 " + event.getProcessedCount() + "건, 신규 "
			+ event.getNewCount() + "건";
	}

	private void warnIfStale(MarketType market, String marketType, String actionType) {
		String syncKey = syncKey(market);
		Optional<LocalDateTime> lastNewAt = syncStatusService.lastNewAt(syncKey);
		if (lastNewAt.isEmpty()) {
			return;
		}
		LocalDateTime now = LocalDateTime.now();
		long ordersInWindow = orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(
			countedMarkets(market), now.minusDays(SyncFreshnessPolicy.WINDOW_DAYS));
		Optional<Duration> stale = SyncFreshnessPolicy.staleness(
			ordersInWindow, SyncFreshnessPolicy.WINDOW_DAYS, lastNewAt.get(), now);
		if (stale.isEmpty()) {
			return;
		}
		long days = stale.get().toDays();
		Duration threshold = SyncFreshnessPolicy.threshold(ordersInWindow, SyncFreshnessPolicy.WINDOW_DAYS);
		String message = "신규 주문이 " + days + "일째 0건이다 (임계 "
			+ threshold.toDays() + "일). 마지막 신규 유입 "
			+ lastNewAt.get() + " — 마켓 연동 상태를 확인하라";
		log.warn("[{}] {}", marketType, message);
		actionLogService.record(actionType + "_STALE", marketType, ActionStatus.WARNING, message);
	}

	private String syncKey(MarketType market) {
		return switch (market) {
			case GMARKET, AUCTION -> SyncMarketKeys.GMARKET;
			case COUPANG -> SyncMarketKeys.COUPANG;
			case SMART_STORE -> SyncMarketKeys.SMART_STORE;
			case ELEVEN_STREET -> SyncMarketKeys.ELEVEN_STREET;
			default -> market.name();
		};
	}

	private List<MarketType> countedMarkets(MarketType market) {
		return switch (market) {
			case GMARKET, AUCTION -> List.of(MarketType.GMARKET, MarketType.AUCTION);
			default -> List.of(market);
		};
	}
}

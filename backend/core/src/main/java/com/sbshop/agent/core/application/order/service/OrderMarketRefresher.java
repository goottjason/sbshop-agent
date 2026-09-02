package com.sbshop.agent.core.application.order.service;

import java.time.LocalDate;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderMarketRefresher {
	static final int BULK_LOOKBACK_DAYS = 7;

	private final OrderReconciliationService reconciliationService;
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final Cafe24OrderSyncService cafe24OrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;

	public void refreshOne(Order order) {
		try {
			reconciliationService.reconcileOne(order);
		} catch (Exception e) {
			log.warn("명령 후 재조회 실패: orderNo={} — 명령은 이미 마켓에 나갔다. 다음 확증이 갱신한다: {}",
				order.getMarketOrderNo(), e.getMessage());
		}
	}

	public void refreshAfterBulk(Set<MarketType> marketTypes) {
		if (marketTypes == null || marketTypes.isEmpty()) {
			return;
		}
		LocalDate to = LocalDate.now();
		LocalDate from = to.minusDays(BULK_LOOKBACK_DAYS);

		if (marketTypes.contains(MarketType.COUPANG)) {
			runQuietly("COUPANG", () -> coupangOrderSyncService.syncCoupangOrders(from, to, false));
		}
		if (marketTypes.contains(MarketType.GMARKET) || marketTypes.contains(MarketType.AUCTION)
			|| marketTypes.contains(MarketType.CAFE24)) {
			runQuietly("CAFE24", () -> cafe24OrderSyncService.syncCafe24Orders(from, to, false));
		}
		if (marketTypes.contains(MarketType.ELEVEN_STREET)) {
			runQuietly("ELEVEN_STREET", () -> elevenstOrderSyncService.syncElevenstOrders(from, to, false));
		}
		if (marketTypes.contains(MarketType.SMART_STORE)) {
			runQuietly("SMART_STORE", () -> smartStoreOrderSyncService.syncSmartStoreOrders(from, to, false));
		}
	}

	private void runQuietly(String label, Runnable task) {
		try {
			task.run();
		} catch (Exception e) {
			log.error("일괄 후 목록 갱신 실패({}): {}", label, e.getMessage(), e);
		}
	}
}

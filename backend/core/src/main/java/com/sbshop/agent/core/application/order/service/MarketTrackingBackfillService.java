package com.sbshop.agent.core.application.order.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketTrackingBackfillService {
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final Cafe24OrderSyncService cafe24OrderSyncService;

	private static final int COUPANG_WINDOW = 30;
	private static final int SMARTSTORE_WINDOW = 30;
	private static final int ELEVENST_WINDOW = 30;
	private static final int CAFE24_WINDOW = 60;

	long pauseBetweenWindowsMs = 5_000L;

	@Async("syncTaskExecutor")
	public void backfill(int days) {
		LocalDate today = LocalDate.now();
		LocalDate start = today.minusDays(days);
		log.info("[백필] 마켓 보유 송장 백필 시작: {} ~ {} ({}일)", start, today, days);

		Map<String, Integer> done = new LinkedHashMap<>();
		done.put("COUPANG", walk("쿠팡", start, today, COUPANG_WINDOW,
			(from, to) -> coupangOrderSyncService.syncCoupangOrders(from, to, false)));
		done.put("SMART_STORE", walk("스마트스토어", start, today, SMARTSTORE_WINDOW,
			(from, to) -> smartStoreOrderSyncService.syncSmartStoreOrders(from, to, false)));
		done.put("ELEVEN_STREET", walk("11번가", start, today, ELEVENST_WINDOW,
			(from, to) -> elevenstOrderSyncService.syncElevenstOrders(from, to, false)));
		done.put("GMARKET_AUCTION", walk("G마켓·옥션", start, today, CAFE24_WINDOW,
			(from, to) -> cafe24OrderSyncService.syncCafe24Orders(from, to, false)));

		log.info("[백필] 마켓 보유 송장 백필 완료: 구간 성공 수 {}", done);
	}

	private int walk(String marketName, LocalDate start, LocalDate today, int windowDays,
		BiConsumer<LocalDate, LocalDate> sync) {
		int ok = 0;
		LocalDate from = start;
		while (!from.isAfter(today)) {
			LocalDate to = from.plusDays(windowDays).isAfter(today) ? today : from.plusDays(windowDays);
			try {
				sync.accept(from, to);
				ok++;
				log.info("[백필] {} 구간 완료: {} ~ {}", marketName, from, to);
			} catch (Exception e) {
				log.warn("[백필] {} 구간 실패({} ~ {}): {}", marketName, from, to, e.getMessage());
			}
			if (to.isEqual(today)) {
				break;
			}
			from = to.plusDays(1);
			pause();
		}
		return ok;
	}

	private void pause() {
		try {
			if (pauseBetweenWindowsMs <= 0) {
				return;
			}
			Thread.sleep(pauseBetweenWindowsMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}

package com.sbshop.agent.core.application.order.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 마켓 보유 송장 백필 — 네 마켓 동기화를 과거 구간까지 다시 돌려 {@code market_tracking_no}를 채운다.
 *
 * <p>배경(D-159): 이 컬럼은 D-148에서 신설됐다. 그전에 30일 조회 창을 벗어난 주문은 값을 가질 기회가
 * 없었고, 그래서 화면이 반영 여부를 판정하지 못한다. 창 안 주문은 전 마켓 100% 수집되고 있으므로
 * <b>새 수집 경로가 아니라 과거 구간 재실행</b>이 필요한 일이다.
 *
 * <p><b>한 번에 넓은 창으로 부르면 안 된다</b>(2026-08-08 실측으로 확인):
 * <ul>
 *   <li>Cafe24 — 조회 범위 <b>3개월 상한</b>: {@code 422 The date range for Search End Date should be
 *       within 3 months}</li>
 *   <li>쿠팡 — 넓은 범위 × 상태별 조회가 <b>레이트리밋</b>에 걸린다: {@code 429 TOO_MANY_REQUESTS}</li>
 * </ul>
 * 그래서 구간을 나눠 <b>오래된 쪽부터 순차로</b> 걷고, 구간 사이에 쉰다. 각 구간은 검증된 동기화 경로를
 * 그대로 타므로 새 코드 경로가 늘지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketTrackingBackfillService {

	private final CoupangOrderSyncService coupangOrderSyncService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final Cafe24OrderSyncService cafe24OrderSyncService;

	/** 마켓별 안전 구간 크기(일). 위 실측 제약에서 나온 값이다. */
	private static final int COUPANG_WINDOW = 30;      // 429 회피
	private static final int SMARTSTORE_WINDOW = 30;
	private static final int ELEVENST_WINDOW = 30;     // 어댑터가 다시 7일로 쪼갠다
	private static final int CAFE24_WINDOW = 60;       // 3개월 상한에 여유를 둔다

	/**
	 * 구간 사이 대기(ms). 레이트리밋에 걸리면 그 마켓의 남은 구간이 통째로 무의미해진다.
	 * 테스트에서 0으로 낮춰 실시간 대기 없이 분할 계약만 검증한다(회귀 시간을 잡아먹지 않게).
	 */
	long pauseBetweenWindowsMs = 5_000L;

	/**
	 * 최근 {@code days}일을 마켓별 안전 구간으로 나눠 순차 백필한다.
	 *
	 * <p>한 마켓의 실패가 나머지를 막지 않는다 — 백필은 전부-또는-전무일 이유가 없다.
	 */
	@Async("syncTaskExecutor")
	public void backfill(int days) {
		LocalDate today = LocalDate.now();
		LocalDate start = today.minusDays(days);
		log.info("[백필] 마켓 보유 송장 백필 시작: {} ~ {} ({}일)", start, today, days);

		Map<String, Integer> done = new LinkedHashMap<>();
		done.put("COUPANG", walk("쿠팡", start, today, COUPANG_WINDOW,
			coupangOrderSyncService::syncCoupangOrders));
		done.put("SMART_STORE", walk("스마트스토어", start, today, SMARTSTORE_WINDOW,
			smartStoreOrderSyncService::syncSmartStoreOrders));
		done.put("ELEVEN_STREET", walk("11번가", start, today, ELEVENST_WINDOW,
			elevenstOrderSyncService::syncElevenstOrders));
		done.put("GMARKET_AUCTION", walk("G마켓·옥션", start, today, CAFE24_WINDOW,
			cafe24OrderSyncService::syncCafe24Orders));

		log.info("[백필] 마켓 보유 송장 백필 완료: 구간 성공 수 {}", done);
	}

	/** 구간을 오래된 쪽부터 걷는다. 반환값은 성공한 구간 수(실패는 로그로 남기고 계속 간다). */
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
				// 구간 하나가 실패해도 다음 구간은 시도한다 — 실패 구간만 비고 나머지는 채워진다.
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

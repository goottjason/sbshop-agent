package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 백필의 구간 분할 계약(D-159 보정).
 *
 * <p>2026-08-08 실측: 한 번에 넓은 창으로 부르면 <b>Cafe24는 3개월 상한(422)</b>,
 * <b>쿠팡은 레이트리밋(429)</b>에 걸린다. 그래서 마켓별 안전 구간으로 나눠 걸어야 하고,
 * 그 성질이 깨지면 백필이 조용히 아무것도 채우지 못한다 — 계약으로 고정한다.
 */
class MarketTrackingBackfillServiceTest {

	private CoupangOrderSyncService coupang;
	private SmartStoreOrderSyncService smartStore;
	private ElevenstOrderSyncService elevenst;
	private Cafe24OrderSyncService cafe24;
	private MarketTrackingBackfillService service;

	@BeforeEach
	void setUp() {
		coupang = mock(CoupangOrderSyncService.class);
		smartStore = mock(SmartStoreOrderSyncService.class);
		elevenst = mock(ElevenstOrderSyncService.class);
		cafe24 = mock(Cafe24OrderSyncService.class);
		service = new MarketTrackingBackfillService(coupang, smartStore, elevenst, cafe24);
		service.pauseBetweenWindowsMs = 0;   // 페이싱은 운영 값이고, 여기서 검증하는 계약은 구간 분할이다
	}

	private List<Long> windowLengths(ArgumentCaptor<LocalDate> from, ArgumentCaptor<LocalDate> to) {
		List<Long> lengths = new ArrayList<>();
		for (int i = 0; i < from.getAllValues().size(); i++) {
			lengths.add(ChronoUnit.DAYS.between(from.getAllValues().get(i), to.getAllValues().get(i)));
		}
		return lengths;
	}

	@Test
	@DisplayName("쿠팡은 30일 이하 구간으로 나눠 부른다 — 넓은 범위는 429를 부른다")
	void splitsCoupangIntoSafeWindows() {
		service.backfill(120);

		ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
		// 계약은 "구간이 몇 개인가"가 아니라 "각 구간이 안전 크기 이하인가"다.
		verify(coupang, org.mockito.Mockito.atLeastOnce())
			.syncCoupangOrders(from.capture(), to.capture(), org.mockito.ArgumentMatchers.eq(false));
		assertThat(windowLengths(from, to)).isNotEmpty().allMatch(len -> len <= 30);
	}

	@Test
	@DisplayName("Cafe24는 3개월 상한 안쪽 구간으로 나눠 부른다")
	void splitsCafe24WithinThreeMonthCap() {
		service.backfill(180);

		ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
		verify(cafe24, org.mockito.Mockito.atLeastOnce())
			.syncCafe24Orders(from.capture(), to.capture(), org.mockito.ArgumentMatchers.eq(false));
		assertThat(windowLengths(from, to)).allMatch(len -> len <= 89);
	}

	@Test
	@DisplayName("구간 전체가 오늘까지 덮인다 — 최근 구간이 빠지면 백필이 반쪽이 된다")
	void coversWholeRangeUpToToday() {
		service.backfill(70);

		ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
		verify(elevenst, org.mockito.Mockito.atLeastOnce())
			.syncElevenstOrders(from.capture(), to.capture(), org.mockito.ArgumentMatchers.eq(false));

		assertThat(from.getAllValues().get(0)).isEqualTo(LocalDate.now().minusDays(70));
		assertThat(to.getAllValues().get(to.getAllValues().size() - 1)).isEqualTo(LocalDate.now());
	}

	@Test
	@DisplayName("백필은 네 마켓 모두 갱신 전용으로 부른다 — 옛 주문을 새로 만들지 않는다")
	void alwaysRunsInUpdateOnlyMode() {
		// 2026-08-08: 백필이 주문을 생성하는 바람에 쿠팡 272·스토어 16건이 유입돼 수동 정리해야 했다.
		// 백필의 목적은 "이미 가진 주문의 마켓 값 갱신"이지 과거 주문 수집이 아니다.
		service.backfill(60);

		verify(coupang, org.mockito.Mockito.atLeastOnce())
			.syncCoupangOrders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
		verify(smartStore, org.mockito.Mockito.atLeastOnce())
			.syncSmartStoreOrders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
		verify(elevenst, org.mockito.Mockito.atLeastOnce())
			.syncElevenstOrders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
		verify(cafe24, org.mockito.Mockito.atLeastOnce())
			.syncCafe24Orders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
	}

	@Test
	@DisplayName("한 마켓이 실패해도 나머지 마켓은 계속 걷는다")
	void oneMarketFailureDoesNotStopOthers() {
		doThrow(new RuntimeException("429 TOO_MANY_REQUESTS"))
			.when(coupang).syncCoupangOrders(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

		service.backfill(60);

		verify(smartStore, org.mockito.Mockito.atLeastOnce()).syncSmartStoreOrders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
		verify(elevenst, org.mockito.Mockito.atLeastOnce()).syncElevenstOrders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
		verify(cafe24, org.mockito.Mockito.atLeastOnce()).syncCafe24Orders(any(), any(), org.mockito.ArgumentMatchers.eq(false));
	}

	@Test
	@DisplayName("구간 하나가 실패해도 같은 마켓의 다음 구간은 시도한다")
	void oneWindowFailureDoesNotStopRemainingWindows() {
		doThrow(new RuntimeException("일시 오류"))
			.doNothing()
			.when(smartStore).syncSmartStoreOrders(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());

		service.backfill(90);

		verify(smartStore, org.mockito.Mockito.atLeast(2)).syncSmartStoreOrders(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
	}
}

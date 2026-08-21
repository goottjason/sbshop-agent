package com.sbshop.agent.core.application.order.service;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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
		service.pauseBetweenWindowsMs = 0;
	}

	@Test
	@DisplayName("쿠팡은 30일 이하 구간으로 나눠 부른다 — 넓은 범위는 429를 부른다")
	void splitsCoupangIntoSafeWindows() {
		service.backfill(120);

		ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
		verify(coupang, Mockito.atLeastOnce())
			.syncCoupangOrders(from.capture(), to.capture(), ArgumentMatchers.eq(false));
		assertThat(windowLengths(from, to)).isNotEmpty().allMatch(len -> len <= 30);
	}

	@Test
	@DisplayName("Cafe24는 3개월 상한 안쪽 구간으로 나눠 부른다")
	void splitsCafe24WithinThreeMonthCap() {
		service.backfill(180);

		ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
		verify(cafe24, Mockito.atLeastOnce())
			.syncCafe24Orders(from.capture(), to.capture(), ArgumentMatchers.eq(false));
		assertThat(windowLengths(from, to)).allMatch(len -> len <= 89);
	}

	@Test
	@DisplayName("구간 전체가 오늘까지 덮인다 — 최근 구간이 빠지면 백필이 반쪽이 된다")
	void coversWholeRangeUpToToday() {
		service.backfill(70);

		ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
		ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
		verify(elevenst, Mockito.atLeastOnce())
			.syncElevenstOrders(from.capture(), to.capture(), ArgumentMatchers.eq(false));

		assertThat(from.getAllValues().get(0)).isEqualTo(LocalDate.now().minusDays(70));
		assertThat(to.getAllValues().get(to.getAllValues().size() - 1)).isEqualTo(LocalDate.now());
	}

	@Test
	@DisplayName("백필은 네 마켓 모두 갱신 전용으로 부른다 — 옛 주문을 새로 만들지 않는다")
	void alwaysRunsInUpdateOnlyMode() {
		service.backfill(60);

		verify(coupang, Mockito.atLeastOnce())
			.syncCoupangOrders(any(), any(), ArgumentMatchers.eq(false));
		verify(smartStore, Mockito.atLeastOnce())
			.syncSmartStoreOrders(any(), any(), ArgumentMatchers.eq(false));
		verify(elevenst, Mockito.atLeastOnce())
			.syncElevenstOrders(any(), any(), ArgumentMatchers.eq(false));
		verify(cafe24, Mockito.atLeastOnce())
			.syncCafe24Orders(any(), any(), ArgumentMatchers.eq(false));
	}

	@Test
	@DisplayName("한 마켓이 실패해도 나머지 마켓은 계속 걷는다")
	void oneMarketFailureDoesNotStopOthers() {
		doThrow(new RuntimeException("429 TOO_MANY_REQUESTS"))
			.when(coupang).syncCoupangOrders(any(), any(), ArgumentMatchers.anyBoolean());

		service.backfill(60);

		verify(smartStore, Mockito.atLeastOnce()).syncSmartStoreOrders(any(), any(),
			ArgumentMatchers.eq(false));
		verify(elevenst, Mockito.atLeastOnce()).syncElevenstOrders(any(), any(),
			ArgumentMatchers.eq(false));
		verify(cafe24, Mockito.atLeastOnce()).syncCafe24Orders(any(), any(),
			ArgumentMatchers.eq(false));
	}

	@Test
	@DisplayName("구간 하나가 실패해도 같은 마켓의 다음 구간은 시도한다")
	void oneWindowFailureDoesNotStopRemainingWindows() {
		doThrow(new RuntimeException("일시 오류"))
			.doNothing()
			.when(smartStore).syncSmartStoreOrders(any(), any(), ArgumentMatchers.anyBoolean());

		service.backfill(90);

		verify(smartStore, Mockito.atLeast(2)).syncSmartStoreOrders(any(), any(),
			ArgumentMatchers.anyBoolean());
	}

	private List<Long> windowLengths(ArgumentCaptor<LocalDate> from, ArgumentCaptor<LocalDate> to) {
		List<Long> lengths = new ArrayList<>();
		for (int i = 0; i < from.getAllValues().size(); i++) {
			lengths.add(ChronoUnit.DAYS.between(from.getAllValues().get(i), to.getAllValues().get(i)));
		}
		return lengths;
	}
}

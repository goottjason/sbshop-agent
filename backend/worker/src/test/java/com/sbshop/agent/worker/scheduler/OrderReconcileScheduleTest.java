package com.sbshop.agent.worker.scheduler;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.worker.service.EmailFetcherService;

@ExtendWith(MockitoExtension.class)
class OrderReconcileScheduleTest {

	@Mock
	EmailFetcherService emailFetcherService;
	@Mock
	SmartStoreOrderSyncService smartStoreOrderSyncService;
	@Mock
	CoupangOrderSyncService coupangOrderSyncService;
	@Mock
	ElevenstOrderSyncService elevenstOrderSyncService;
	@Mock
	Cafe24OrderSyncService cafe24OrderSyncService;
	@Mock
	CustomsOrderSyncService customsOrderSyncService;
	@Mock
	SyncStatusService syncStatusService;

	@InjectMocks
	OrderSyncScheduler scheduler;

	@Test
	@DisplayName("장기 재동기화는 4개 마켓 전부를 90일 창·갱신전용(createMissing=false)으로 부른다")
	void reconcile_callsAllMarketsWithLongWindowUpdateOnly() {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS);

		scheduler.reconcileOpenOrders();

		verify(coupangOrderSyncService).syncCoupangOrders(eq(from), eq(today), eq(false));
		verify(cafe24OrderSyncService).syncCafe24Orders(eq(from), eq(today), eq(false));
		verify(smartStoreOrderSyncService).syncSmartStoreOrders(eq(from), eq(today), eq(false));
		verify(elevenstOrderSyncService).syncElevenstOrders(eq(from), eq(today), eq(false));
	}

	@Test
	@DisplayName("한 마켓이 실패해도 나머지 마켓 재동기화는 계속한다")
	void reconcile_oneMarketFails_othersStillRun() {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS);
		doThrow(new RuntimeException("쿠팡 조회 실패"))
			.when(coupangOrderSyncService).syncCoupangOrders(eq(from), eq(today), eq(false));

		scheduler.reconcileOpenOrders();

		verify(cafe24OrderSyncService).syncCafe24Orders(eq(from), eq(today), eq(false));
		verify(smartStoreOrderSyncService).syncSmartStoreOrders(eq(from), eq(today), eq(false));
		verify(elevenstOrderSyncService).syncElevenstOrders(eq(from), eq(today), eq(false));
	}

	@Test
	@DisplayName("재동기화 창은 30분 주기 동기화 창(30일)보다 길다")
	void reconcile_windowIsLongerThanRoutineSync() {
		org.junit.jupiter.api.Assertions.assertTrue(
			OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS > 30,
			"장기 재동기화 창이 30일 이하이면 정기 동기화와 같은 사각지대를 갖는다");
	}
}

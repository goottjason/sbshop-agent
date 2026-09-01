package com.sbshop.agent.worker.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.OrderReconciliationService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.worker.service.EmailFetcherService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
	@Mock
	OrderReconciliationService orderReconciliationService;

	@InjectMocks
	OrderSyncScheduler scheduler;

	@Test
	@DisplayName("확증 주기는 120일 창을 갱신전용으로 훑는다")
	void reconcile_usesLongWindowUpdateOnly() {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS);

		scheduler.reconcileOrders();

		verify(coupangOrderSyncService).syncCoupangOrders(eq(from), eq(today), eq(false));
		verify(cafe24OrderSyncService).syncCafe24Orders(eq(from), eq(today), eq(false));
		verify(elevenstOrderSyncService).syncElevenstOrders(eq(from), eq(today), eq(false));
	}

	@Test
	@DisplayName("목록 조회 뒤 프로브가 있는 마켓에 확증을 돌린다")
	void reconcile_runsProbeLayer() {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS);

		scheduler.reconcileOrders();

		verify(orderReconciliationService).reconcile(eq(MarketType.COUPANG), eq(from), eq(today), any());
		verify(orderReconciliationService).reconcile(eq(MarketType.GMARKET), eq(from), eq(today), any());
		verify(orderReconciliationService).reconcile(eq(MarketType.AUCTION), eq(from), eq(today), any());
		verify(orderReconciliationService).reconcile(eq(MarketType.ELEVEN_STREET), eq(from), eq(today), any());
	}

	@Test
	@DisplayName("한 마켓이 실패해도 나머지 확증은 계속한다")
	void reconcile_oneMarketFails_othersStillRun() {
		LocalDate today = LocalDate.now();
		LocalDate from = today.minusDays(OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS);
		doThrow(new RuntimeException("쿠팡 조회 실패"))
			.when(coupangOrderSyncService).syncCoupangOrders(eq(from), eq(today), eq(false));

		scheduler.reconcileOrders();

		verify(cafe24OrderSyncService).syncCafe24Orders(eq(from), eq(today), eq(false));
		verify(elevenstOrderSyncService).syncElevenstOrders(eq(from), eq(today), eq(false));
		verify(orderReconciliationService).reconcile(eq(MarketType.ELEVEN_STREET), eq(from), eq(today), any());
	}

	@Test
	@DisplayName("확증 창은 정기 동기화 창(30일)보다 길다")
	void reconcile_windowIsLongerThanRoutineSync() {
		org.junit.jupiter.api.Assertions.assertTrue(
			OrderSyncScheduler.RECONCILE_LOOKBACK_DAYS > 30,
			"확증 창이 30일 이하이면 정기 동기화와 같은 사각지대를 갖는다");
	}

	@Test
	@DisplayName("스마트스토어는 확증 대상이 아니다 — 변경일 기준이라 목록으로 잡힌다")
	void reconcile_skipsSmartStore() {
		scheduler.reconcileOrders();

		verify(orderReconciliationService, org.mockito.Mockito.never())
			.reconcile(eq(MarketType.SMART_STORE), any(), any(), any());
	}
}

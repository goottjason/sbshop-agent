package com.sbshop.agent.worker.scheduler;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.worker.service.EmailFetcherService;

@ExtendWith(MockitoExtension.class)
class OrderSyncSchedulerSyncStatusTest {

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
	@DisplayName("스케줄 실행됨(true) → markRunning(EMAIL) 후 markCompleted(EMAIL) 순서로 기록")
	void executed_recordsRunningThenCompleted() {
		when(emailFetcherService.fetchAndProcessEmails()).thenReturn(true);

		scheduler.syncOrders();

		InOrder inOrder = inOrder(syncStatusService, emailFetcherService);
		inOrder.verify(syncStatusService).markRunning(SyncMarketKeys.EMAIL);
		inOrder.verify(emailFetcherService).fetchAndProcessEmails();
		inOrder.verify(syncStatusService).markCompleted(SyncMarketKeys.EMAIL);
	}

	@Test
	@DisplayName("재진입 가드로 스킵(false) → markRunning만 기록, markCompleted 미기록")
	void skipped_doesNotRecordCompleted() {
		when(emailFetcherService.fetchAndProcessEmails()).thenReturn(false);

		scheduler.syncOrders();

		verify(syncStatusService).markRunning(SyncMarketKeys.EMAIL);
		verify(syncStatusService, never()).markCompleted(SyncMarketKeys.EMAIL);
	}

	@Test
	@DisplayName("스케줄 실행 실패 → markFailed(EMAIL, 사유) 기록, markCompleted 미기록")
	void failure_recordsFailed() {
		doThrow(new RuntimeException("boom")).when(emailFetcherService).fetchAndProcessEmails();

		scheduler.syncOrders();

		verify(syncStatusService).markRunning(SyncMarketKeys.EMAIL);
		verify(syncStatusService).markFailed(eq(SyncMarketKeys.EMAIL), contains("boom"));
		verify(syncStatusService, never()).markCompleted(SyncMarketKeys.EMAIL);
	}
}

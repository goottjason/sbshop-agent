package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;

/**
 * D-087: @Async 마켓동기화 트리거의 ActionLog SUCCESS 오기록 교정.
 *
 * <p>동기화 서비스(S1~S4)는 {@code @Async}라 즉시 반환되므로, 컨트롤러가 디스패치 직후
 * SUCCESS를 남기면 실제 결과와 무관하게 항상 SUCCESS가 된다. 완료 기록은
 * {@code SyncCompletedEvent} → {@code ActionLogSyncListener}가 정확히 담당하므로,
 * 컨트롤러는 <b>STARTED만</b> 남기고 SUCCESS는 남기지 않아야 한다.
 * (동기 디스패치 예외 시에만 FAILED — 이 경우 async 본문·이벤트가 실행되지 않기 때문.)
 *
 * <p>정산(S5)은 {@code SyncCompletedEvent}를 발행하지 않으므로 완료 기록은
 * {@code CoupangOrderSyncService.syncCoupangSettlement} 서비스 내부가 담당한다
 * (별도 서비스 테스트로 검증). 컨트롤러는 마찬가지로 SUCCESS를 남기지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class OrderSyncControllerActionLogTest {

	@Mock
	CoupangOrderSyncService coupangOrderSyncService;
	@Mock
	SmartStoreOrderSyncService smartStoreOrderSyncService;
	@Mock
	ElevenstOrderSyncService elevenstOrderSyncService;
	@Mock
	Cafe24OrderSyncService cafe24OrderSyncService;
	@Mock
	Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock
	CustomsOrderSyncService customsOrderSyncService;
	@Mock
	SyncStatusService syncStatusService;
	@Mock
	ActionLogService actionLogService;

	private OrderSyncController controller() {
		return new OrderSyncController(coupangOrderSyncService, smartStoreOrderSyncService,
			elevenstOrderSyncService, cafe24OrderSyncService, cafe24OrderApiPort,
			customsOrderSyncService, syncStatusService, actionLogService);
	}

	// ---- 쿠팡 ----
	@Test
	@DisplayName("syncCoupangOrders 트리거 → STARTED만 기록, SUCCESS는 컨트롤러가 남기지 않음(리스너 위임)")
	void coupang_trigger_recordsStartedNotSuccess() {
		controller().syncCoupangOrders();
		verify(actionLogService).record(eq("COUPANG_SYNC"), eq("COUPANG"),
			eq(ActionStatus.STARTED), any());
		verify(actionLogService, never()).record(eq("COUPANG_SYNC"), eq("COUPANG"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncCoupangOrders 동기 디스패치 예외 → COUPANG_SYNC FAILED 기록")
	void coupang_dispatchFailure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(coupangOrderSyncService).syncCoupangOrders();
		controller().syncCoupangOrders();
		verify(actionLogService).record(eq("COUPANG_SYNC"), eq("COUPANG"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- 스마트스토어 ----
	@Test
	@DisplayName("syncSmartStoreOrders 트리거 → STARTED만 기록, SUCCESS 미기록")
	void smartstore_trigger_recordsStartedNotSuccess() {
		controller().syncSmartStoreOrders();
		verify(actionLogService).record(eq("SMART_STORE_SYNC"), eq("SMART_STORE"),
			eq(ActionStatus.STARTED), any());
		verify(actionLogService, never()).record(eq("SMART_STORE_SYNC"), eq("SMART_STORE"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncSmartStoreOrders 동기 디스패치 예외 → SMART_STORE_SYNC FAILED 기록")
	void smartstore_dispatchFailure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(smartStoreOrderSyncService).syncSmartStoreOrders();
		controller().syncSmartStoreOrders();
		verify(actionLogService).record(eq("SMART_STORE_SYNC"), eq("SMART_STORE"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- 11번가 ----
	@Test
	@DisplayName("syncElevenStreetOrders 트리거 → STARTED만 기록, SUCCESS 미기록")
	void elevenstreet_trigger_recordsStartedNotSuccess() {
		controller().syncElevenStreetOrders();
		verify(actionLogService).record(eq("ELEVEN_STREET_SYNC"), eq("ELEVEN_STREET"),
			eq(ActionStatus.STARTED), any());
		verify(actionLogService, never()).record(eq("ELEVEN_STREET_SYNC"), eq("ELEVEN_STREET"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncElevenStreetOrders 동기 디스패치 예외 → ELEVEN_STREET_SYNC FAILED 기록")
	void elevenstreet_dispatchFailure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(elevenstOrderSyncService).syncElevenstOrders();
		controller().syncElevenStreetOrders();
		verify(actionLogService).record(eq("ELEVEN_STREET_SYNC"), eq("ELEVEN_STREET"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- G마켓/옥션(Cafe24) ----
	@Test
	@DisplayName("syncEsmplusOrders 트리거 → STARTED만 기록, SUCCESS 미기록")
	void esmplus_trigger_recordsStartedNotSuccess() {
		controller().syncEsmplusOrders();
		verify(actionLogService).record(eq("GMARKET_SYNC"), eq("GMARKET"),
			eq(ActionStatus.STARTED), any());
		verify(actionLogService, never()).record(eq("GMARKET_SYNC"), eq("GMARKET"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncEsmplusOrders 동기 디스패치 예외 → GMARKET_SYNC FAILED 기록")
	void esmplus_dispatchFailure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(cafe24OrderSyncService).syncCafe24Orders();
		controller().syncEsmplusOrders();
		verify(actionLogService).record(eq("GMARKET_SYNC"), eq("GMARKET"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- 쿠팡 정산 (완료기록은 서비스가 담당 — 컨트롤러는 SUCCESS 미기록) ----
	@Test
	@DisplayName("syncCoupangSettlement 트리거 → STARTED만 기록, SUCCESS는 컨트롤러가 남기지 않음")
	void settlement_trigger_recordsStartedNotSuccess() {
		controller().syncCoupangSettlement();
		verify(actionLogService).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.STARTED), any());
		verify(actionLogService, never()).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncCoupangSettlement 동기 디스패치 예외 → COUPANG_SETTLEMENT_SYNC FAILED 기록")
	void settlement_dispatchFailure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(coupangOrderSyncService).syncCoupangSettlement();
		controller().syncCoupangSettlement();
		verify(actionLogService).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.FAILED), any());
	}
}

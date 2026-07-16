package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;

/**
 * F-SYNC-3: 각 마켓 동기화 트리거 엔드포인트가 STARTED만이 아니라
 * 호출 성공 시 SUCCESS·예외 시 FAILED도 액션로그로 기록하는지 검증한다.
 * 액션문자열은 기존 인라인 문자열과 동일해야 한다.
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
	@DisplayName("syncCoupangOrders 성공 → COUPANG_SYNC SUCCESS 기록")
	void coupang_success_recordsSuccess() {
		controller().syncCoupangOrders();
		verify(actionLogService).record(eq("COUPANG_SYNC"), eq("COUPANG"),
			eq(ActionStatus.STARTED), any());
		verify(actionLogService).record(eq("COUPANG_SYNC"), eq("COUPANG"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncCoupangOrders 실패 → COUPANG_SYNC FAILED 기록")
	void coupang_failure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(coupangOrderSyncService).syncCoupangOrders();
		controller().syncCoupangOrders();
		verify(actionLogService).record(eq("COUPANG_SYNC"), eq("COUPANG"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- 스마트스토어 ----
	@Test
	@DisplayName("syncSmartStoreOrders 성공 → SMART_STORE_SYNC SUCCESS 기록")
	void smartstore_success_recordsSuccess() {
		controller().syncSmartStoreOrders();
		verify(actionLogService).record(eq("SMART_STORE_SYNC"), eq("SMART_STORE"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncSmartStoreOrders 실패 → SMART_STORE_SYNC FAILED 기록")
	void smartstore_failure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(smartStoreOrderSyncService).syncSmartStoreOrders();
		controller().syncSmartStoreOrders();
		verify(actionLogService).record(eq("SMART_STORE_SYNC"), eq("SMART_STORE"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- 11번가 ----
	@Test
	@DisplayName("syncElevenStreetOrders 성공 → ELEVEN_STREET_SYNC SUCCESS 기록")
	void elevenstreet_success_recordsSuccess() {
		controller().syncElevenStreetOrders();
		verify(actionLogService).record(eq("ELEVEN_STREET_SYNC"), eq("ELEVEN_STREET"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncElevenStreetOrders 실패 → ELEVEN_STREET_SYNC FAILED 기록")
	void elevenstreet_failure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(elevenstOrderSyncService).syncElevenstOrders();
		controller().syncElevenStreetOrders();
		verify(actionLogService).record(eq("ELEVEN_STREET_SYNC"), eq("ELEVEN_STREET"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- G마켓/옥션(Cafe24) ----
	@Test
	@DisplayName("syncEsmplusOrders 성공 → GMARKET_SYNC SUCCESS 기록")
	void esmplus_success_recordsSuccess() {
		controller().syncEsmplusOrders();
		verify(actionLogService).record(eq("GMARKET_SYNC"), eq("GMARKET"),
			eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncEsmplusOrders 실패 → GMARKET_SYNC FAILED 기록")
	void esmplus_failure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(cafe24OrderSyncService).syncCafe24Orders();
		controller().syncEsmplusOrders();
		verify(actionLogService).record(eq("GMARKET_SYNC"), eq("GMARKET"),
			eq(ActionStatus.FAILED), any());
	}

	// ---- 쿠팡 정산 ----
	@Test
	@DisplayName("syncCoupangSettlement 성공 → COUPANG_SETTLEMENT_SYNC SUCCESS 기록")
	void settlement_success_recordsSuccess() {
		controller().syncCoupangSettlement();
		verify(actionLogService).record(
			eq(com.sbshop.agent.core.domain.actionlog.ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("syncCoupangSettlement 실패 → COUPANG_SETTLEMENT_SYNC FAILED 기록")
	void settlement_failure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(coupangOrderSyncService).syncCoupangSettlement();
		controller().syncCoupangSettlement();
		verify(actionLogService).record(
			eq(com.sbshop.agent.core.domain.actionlog.ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.FAILED), any());
	}
}

package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ProductSyncControllerActionLogTest {

	@Mock
	ProductSyncService productSyncService;
	@Mock
	ActionLogService actionLogService;

	private ProductSyncController controller(String configuredToken) {
		return new ProductSyncController(productSyncService, actionLogService,
			new InternalAccessGuard(configuredToken));
	}

	@Test
	@DisplayName("재고 동기화 트리거 → STOCK_SYNC STARTED만 기록, FAILED 미기록")
	void trigger_recordsStartedNotFailed() {
		controller("").syncAllProductStock(null);

		verify(actionLogService).record(eq(ActionLogConstants.STOCK_SYNC), isNull(),
			eq(ActionStatus.STARTED), any());
		verify(actionLogService, never()).record(eq(ActionLogConstants.STOCK_SYNC), isNull(),
			eq(ActionStatus.FAILED), any());
	}

	@Test
	@DisplayName("동기 디스패치 예외 → STOCK_SYNC FAILED 기록")
	void dispatchFailure_recordsFailed() {
		doThrow(new IllegalStateException("boom")).when(productSyncService)
			.syncStockForPreparingOrdersAsync();

		controller("").syncAllProductStock(null);

		verify(actionLogService).record(eq(ActionLogConstants.STOCK_SYNC), isNull(),
			eq(ActionStatus.FAILED), any());
	}

	@Test
	@DisplayName("메시지 없는 예외 → 500 응답 본문이 NPE 없이 기본 문구를 담는다")
	void dispatchFailureWithNullMessage_returnsFallbackBody() {
		doThrow(new IllegalStateException()).when(productSyncService)
			.syncStockForPreparingOrdersAsync();

		ResponseEntity<Map<String, Object>> res = controller("").syncAllProductStock(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(res.getBody()).containsEntry("success", false);
		assertThat(res.getBody().get("message")).isNotNull();
	}

	@Test
	@DisplayName("메시지 없는 예외 → STOCK_SYNC FAILED 기록은 그대로 남는다")
	void dispatchFailureWithNullMessage_recordsFailed() {
		doThrow(new IllegalStateException()).when(productSyncService)
			.syncStockForPreparingOrdersAsync();

		controller("").syncAllProductStock(null);

		verify(actionLogService).record(eq(ActionLogConstants.STOCK_SYNC), isNull(),
			eq(ActionStatus.FAILED), any());
	}

	@Test
	@DisplayName("가드 차단(403) → STARTED/FAILED 어느 활동로그도 기록하지 않는다")
	void forbidden_recordsNothing() {
		ResponseEntity<?> res = controller("s3cr3t").syncAllProductStock("wrong");

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(actionLogService, never()).record(any(), any(), any(), any());
	}
}

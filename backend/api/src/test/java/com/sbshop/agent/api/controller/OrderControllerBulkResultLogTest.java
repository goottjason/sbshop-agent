package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.OrderIdsRequest;
import com.sbshop.agent.api.dto.OrderShipRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.dto.BulkConfirmResult;
import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderControllerBulkResultLogTest {

	@Mock
	private OrderService orderService;
	@Mock
	private OrderShipService orderShipService;
	@Mock
	private ActionLogService actionLogService;

	private OrderController controller() {
		return new OrderController(orderService, orderShipService, actionLogService);
	}

	private ActionStatus capturedStatus(String actionType) {
		ArgumentCaptor<ActionStatus> status = ArgumentCaptor.forClass(ActionStatus.class);
		verify(actionLogService).record(eq(actionType), any(), status.capture(), any());
		return status.getValue();
	}

	@Test
	@DisplayName("일괄 발송 전건 실패: 활동로그가 FAILED로 기록된다(SUCCESS 아님)")
	void shipOrders_allFailed_logsFailed() {
		when(orderShipService.bulkShipOrders(anyList())).thenReturn(
			BulkShipResult.builder()
				.successCount(0).failedCount(2).skippedCount(0)
				.failedIds(List.of(1L, 2L)).errors(List.of("주문 1: x", "주문 2: y"))
				.build());

		OrderShipRequest req = new OrderShipRequest();
		req.setOrderIds(List.of(1L, 2L));
		controller().shipOrders(req);

		assertThat(capturedStatus(ActionLogConstants.ORDER_SHIP)).isEqualTo(ActionStatus.FAILED);
	}

	@Test
	@DisplayName("일괄 발송 부분 실패(성공+실패 혼재): 활동로그가 FAILED로 기록된다")
	void shipOrders_partialFailed_logsFailed() {
		when(orderShipService.bulkShipOrders(anyList())).thenReturn(
			BulkShipResult.builder()
				.successCount(1).failedCount(1).skippedCount(0)
				.failedIds(List.of(2L)).errors(List.of("주문 2: y"))
				.build());

		OrderShipRequest req = new OrderShipRequest();
		req.setOrderIds(List.of(1L, 2L));
		controller().shipOrders(req);

		assertThat(capturedStatus(ActionLogConstants.ORDER_SHIP)).isEqualTo(ActionStatus.FAILED);
	}

	@Test
	@DisplayName("일괄 발송 전건 성공: 활동로그가 SUCCESS로 기록된다")
	void shipOrders_allSuccess_logsSuccess() {
		when(orderShipService.bulkShipOrders(anyList())).thenReturn(
			BulkShipResult.builder()
				.successCount(2).failedCount(0).skippedCount(0)
				.failedIds(List.of()).errors(null)
				.build());

		OrderShipRequest req = new OrderShipRequest();
		req.setOrderIds(List.of(1L, 2L));
		controller().shipOrders(req);

		assertThat(capturedStatus(ActionLogConstants.ORDER_SHIP)).isEqualTo(ActionStatus.SUCCESS);
	}

	@Test
	@DisplayName("일괄 발주확인 전건 실패: 활동로그가 FAILED로 기록된다(현재는 SUCCESS)")
	void bulkConfirm_allFailed_logsFailed() {
		when(orderService.bulkConfirmOrders(anyList())).thenReturn(
			BulkConfirmResult.builder()
				.successCount(0).failedCount(2)
				.failedIds(List.of(1L, 2L)).errors(List.of("Order 1: x", "Order 2: y"))
				.build());

		controller().bulkConfirmOrders(new OrderIdsRequest(List.of(1L, 2L)));

		assertThat(capturedStatus(ActionLogConstants.ORDER_CONFIRM_BATCH)).isEqualTo(ActionStatus.FAILED);
	}

	@Test
	@DisplayName("일괄 발주취소 전건 실패: 활동로그가 FAILED로 기록된다(현재는 SUCCESS)")
	void bulkCancel_allFailed_logsFailed() {
		when(orderService.bulkCancelOrders(anyList())).thenReturn(
			BulkConfirmResult.builder()
				.successCount(0).failedCount(2)
				.failedIds(List.of(1L, 2L)).errors(List.of("Order 1: x", "Order 2: y"))
				.build());

		controller().bulkCancelOrders(new OrderIdsRequest(List.of(1L, 2L)));

		assertThat(capturedStatus(ActionLogConstants.ORDER_CANCEL_BATCH)).isEqualTo(ActionStatus.FAILED);
	}

	@Test
	@DisplayName("일괄 발주확인 전건 성공: 활동로그가 SUCCESS로 기록된다")
	void bulkConfirm_allSuccess_logsSuccess() {
		when(orderService.bulkConfirmOrders(anyList())).thenReturn(
			BulkConfirmResult.builder()
				.successCount(2).failedCount(0)
				.failedIds(List.of()).errors(null)
				.build());

		controller().bulkConfirmOrders(new OrderIdsRequest(List.of(1L, 2L)));

		assertThat(capturedStatus(ActionLogConstants.ORDER_CONFIRM_BATCH)).isEqualTo(ActionStatus.SUCCESS);
	}
}

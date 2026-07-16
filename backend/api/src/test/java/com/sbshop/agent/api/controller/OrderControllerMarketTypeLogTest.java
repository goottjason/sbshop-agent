package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SP-6 / F-ORD-27·F-S6·F-H6·F-ORD-37: 컨트롤러에서 해석 가능한 성공 경로의 활동로그
 * marketType이 null이 아니라 실제 마켓으로 채워지는지 검증한다.
 *
 * <p>대상은 단일 마켓으로 해석되는 성공 경로에 한정한다:
 * 라인아이템 유니패스/소싱/배송 수정. (일괄 발주확인/취소/발송은 다마켓이라 null 유지가 의도.)
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerMarketTypeLogTest {

	@Mock
	private OrderService orderService;
	@Mock
	private OrderShipService orderShipService;
	@Mock
	private ActionLogService actionLogService;

	private OrderController controller() {
		return new OrderController(orderService, orderShipService, actionLogService);
	}

	private String capturedMarketType(String actionType) {
		ArgumentCaptor<String> market = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(eq(actionType), market.capture(),
			eq(ActionStatus.SUCCESS), any());
		return market.getValue();
	}

	private String capturedFailedMarketType(String actionType) {
		ArgumentCaptor<String> market = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(eq(actionType), market.capture(),
			eq(ActionStatus.FAILED), any());
		return market.getValue();
	}

	private OrderLineItem lineItem() {
		return OrderLineItem.builder().orderId(1L).build();
	}

	@Test
	@DisplayName("유니패스 수정 성공: 활동로그 marketType이 라인아이템 마켓으로 채워진다")
	void updateOrderLineItem_recordsResolvedMarketType() {
		when(orderService.updateOrderLineItem(anyLong(), any())).thenReturn(lineItem());
		when(orderService.marketTypeOfLineItem(10L)).thenReturn(MarketType.COUPANG);

		controller().updateOrderLineItem(10L, new com.sbshop.agent.api.dto.OrderLineItemUpdateRequest());

		assertThat(capturedMarketType(ActionLogConstants.UNIPASS_UPDATE)).isEqualTo("COUPANG");
	}

	@Test
	@DisplayName("소싱 수정 성공: 활동로그 marketType이 라인아이템 마켓으로 채워진다")
	void updateSourcingInfo_recordsResolvedMarketType() {
		when(orderService.updateSourcingInfo(anyLong(), any())).thenReturn(lineItem());
		when(orderService.marketTypeOfLineItem(11L)).thenReturn(MarketType.SMART_STORE);

		controller().updateSourcingInfo(11L, new com.sbshop.agent.api.dto.SourcingUpdateRequest());

		assertThat(capturedMarketType(ActionLogConstants.PURCHASE_UPDATE)).isEqualTo("SMART_STORE");
	}

	@Test
	@DisplayName("배송 수정 성공: 활동로그 marketType이 라인아이템 마켓으로 채워진다")
	void updateShippingInfo_recordsResolvedMarketType() {
		when(orderService.updateShippingInfo(anyLong(), any())).thenReturn(lineItem());
		when(orderService.marketTypeOfLineItem(12L)).thenReturn(MarketType.GMARKET);

		controller().updateShippingInfo(12L, new com.sbshop.agent.api.dto.ShippingUpdateRequest());

		assertThat(capturedMarketType(ActionLogConstants.SHIPPING_UPDATE)).isEqualTo("GMARKET");
	}

	// ----- F-ORD-5 / F-ORD-15: 단건 발주확인/취소 실패 경로 marketType 조회 채움 -----

	@Test
	@DisplayName("발주확인 실패: 활동로그 marketType이 주문 조회로 채워진다(null 아님)")
	void confirmOrder_failure_recordsResolvedMarketType() {
		when(orderService.confirmOrder(anyLong())).thenThrow(new RuntimeException("마켓 접수 실패"));
		when(orderService.marketTypeOfOrder(20L)).thenReturn(MarketType.COUPANG);

		try {
			controller().confirmOrder(20L);
		} catch (RuntimeException ignored) {
			// 실패 응답 보존을 위한 재throw는 정상 — 로그 기록만 검증한다.
		}

		assertThat(capturedFailedMarketType(ActionLogConstants.ORDER_CONFIRM)).isEqualTo("COUPANG");
	}

	@Test
	@DisplayName("발주취소 실패: 활동로그 marketType이 주문 조회로 채워진다(null 아님)")
	void cancelOrder_failure_recordsResolvedMarketType() {
		when(orderService.cancelOrder(anyLong())).thenThrow(new RuntimeException("마켓 취소 실패"));
		when(orderService.marketTypeOfOrder(21L)).thenReturn(MarketType.GMARKET);

		try {
			controller().cancelOrder(21L);
		} catch (RuntimeException ignored) {
			// 실패 응답 보존을 위한 재throw는 정상.
		}

		assertThat(capturedFailedMarketType(ActionLogConstants.ORDER_CANCEL)).isEqualTo("GMARKET");
	}

	@Test
	@DisplayName("발주확인 실패 + 주문 조회 불가: 활동로그 marketType은 null로 유지된다")
	void confirmOrder_failure_orderNotFound_marketTypeStaysNull() {
		when(orderService.confirmOrder(anyLong()))
			.thenThrow(new IllegalArgumentException("Order not found: 99"));
		when(orderService.marketTypeOfOrder(99L)).thenReturn(null);

		try {
			controller().confirmOrder(99L);
		} catch (RuntimeException ignored) {
			// 재throw 정상.
		}

		assertThat(capturedFailedMarketType(ActionLogConstants.ORDER_CONFIRM)).isNull();
	}
}

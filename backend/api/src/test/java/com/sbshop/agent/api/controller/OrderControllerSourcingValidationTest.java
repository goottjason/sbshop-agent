package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sbshop.agent.api.dto.SourcingUpdateRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.OrderService;
import com.sbshop.agent.core.application.order.service.OrderShipService;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderControllerSourcingValidationTest {

	@Mock
	private OrderService orderService;
	@Mock
	private OrderShipService orderShipService;
	@Mock
	private ActionLogService actionLogService;

	private OrderController controller() {
		return new OrderController(orderService, orderShipService, actionLogService);
	}

	private SourcingUpdateRequest request(BigDecimal sourcingAmount, BigDecimal logisticsCost) {
		SourcingUpdateRequest req = new SourcingUpdateRequest();
		req.setSourcingAmount(sourcingAmount);
		req.setLogisticsCost(logisticsCost);
		return req;
	}

	@Test
	@DisplayName("소싱금액(sourcingAmount) 음수: 400(IllegalArgumentException)으로 거부하고 서비스 호출 안 함")
	void updateSourcingInfo_rejectsNegativeSourcingAmount() {
		assertThatThrownBy(() -> controller().updateSourcingInfo(11L, request(new BigDecimal("-1"), null)))
			.isInstanceOf(IllegalArgumentException.class);

		verify(orderService, never()).updateSourcingInfo(anyLong(), any());
	}

	@Test
	@DisplayName("물류비(logisticsCost) 음수: 400(IllegalArgumentException)으로 거부하고 서비스 호출 안 함")
	void updateSourcingInfo_rejectsNegativeLogisticsCost() {
		assertThatThrownBy(() -> controller().updateSourcingInfo(11L, request(null, new BigDecimal("-0.01"))))
			.isInstanceOf(IllegalArgumentException.class);

		verify(orderService, never()).updateSourcingInfo(anyLong(), any());
	}

	@Test
	@DisplayName("금액 0/null: 정상값으로 통과(과잉거부 금지) — 서비스 호출됨")
	void updateSourcingInfo_allowsZeroAndNull() {
		lenient().when(orderService.updateSourcingInfo(anyLong(), any()))
			.thenReturn(OrderLineItem.builder().orderId(1L).build());
		lenient().when(orderService.marketTypeOfLineItem(anyLong()))
			.thenReturn(MarketType.SMART_STORE);

		assertThatCode(() -> controller().updateSourcingInfo(11L, request(BigDecimal.ZERO, null)))
			.doesNotThrowAnyException();
		assertThatCode(() -> controller().updateSourcingInfo(11L, request(null, null)))
			.doesNotThrowAnyException();

		verify(orderService, Mockito.times(2)).updateSourcingInfo(anyLong(), any());
	}
}

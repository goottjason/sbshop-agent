package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-S4 / R6: 소싱(구매) 정보 수정 요청의 금액 필드 음수 검증.
 *
 * <p>구매가(sourcingAmount)·물류비(logisticsCost)가 음수면 데이터 오염이므로
 * 진입부에서 {@link IllegalArgumentException}(→400)으로 거부한다.
 * null(미변경)·0(무상 소싱/무물류비)은 정상값으로 통과시켜 과잉거부하지 않는다.
 * F-PROD-8/23·F-PSRC-11의 signum()<0 패턴과 일관.
 */
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
		assertThatThrownBy(() ->
			controller().updateSourcingInfo(11L, request(new BigDecimal("-1"), null)))
			.isInstanceOf(IllegalArgumentException.class);

		verify(orderService, never()).updateSourcingInfo(anyLong(), any());
	}

	@Test
	@DisplayName("물류비(logisticsCost) 음수: 400(IllegalArgumentException)으로 거부하고 서비스 호출 안 함")
	void updateSourcingInfo_rejectsNegativeLogisticsCost() {
		assertThatThrownBy(() ->
			controller().updateSourcingInfo(11L, request(null, new BigDecimal("-0.01"))))
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

		assertThatCode(() ->
			controller().updateSourcingInfo(11L, request(BigDecimal.ZERO, null)))
			.doesNotThrowAnyException();
		assertThatCode(() ->
			controller().updateSourcingInfo(11L, request(null, null)))
			.doesNotThrowAnyException();

		verify(orderService, org.mockito.Mockito.times(2)).updateSourcingInfo(anyLong(), any());
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import com.sbshop.agent.core.domain.order.enums.MarketType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderShipBulkRefreshTest {

	@Mock
	OrderShipProcessor orderShipProcessor;
	@Mock
	OrderMarketRefresher marketRefresher;
	@Mock
	OrderService orderService;

	@InjectMocks
	OrderShipService service;

	@Test
	@DisplayName("일괄 발송 뒤 마켓 목록을 한 번 훑어 상태를 받아온다 — 우리가 찍지 않는다")
	void bulkShipRefreshesByList() {
		when(orderShipProcessor.shipSingleOrder(1L)).thenReturn(OrderShipOutcome.shipped());
		when(orderService.marketTypeOfOrder(1L)).thenReturn(MarketType.COUPANG);

		service.bulkShipOrders(List.of(1L));

		verify(marketRefresher).refreshAfterBulk(Set.of(MarketType.COUPANG));
	}

	@Test
	@DisplayName("전부 실패하면 목록을 훑지 않는다 — 마켓에 아무것도 안 나갔다")
	void allFailedSkipsRefresh() {
		when(orderShipProcessor.shipSingleOrder(1L)).thenReturn(OrderShipOutcome.failed("실패"));

		service.bulkShipOrders(List.of(1L));

		verify(marketRefresher, never()).refreshAfterBulk(any());
	}

	@Test
	@DisplayName("주문 목록이 비면 아무 것도 하지 않는다")
	void nullOrderIdsDoesNothing() {
		service.bulkShipOrders(null);

		verify(marketRefresher, never()).refreshAfterBulk(any());
	}
}

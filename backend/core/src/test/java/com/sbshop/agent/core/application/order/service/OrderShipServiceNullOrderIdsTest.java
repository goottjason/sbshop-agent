package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderShipServiceNullOrderIdsTest {
	private OrderShipProcessor orderShipProcessor;
	private OrderShipService service;

	@BeforeEach
	void setUp() {
		orderShipProcessor = mock(OrderShipProcessor.class);
		service = new OrderShipService(orderShipProcessor, org.mockito.Mockito.mock(OrderMarketRefresher.class), org.mockito.Mockito.mock(OrderService.class));
	}

	@Test
	@DisplayName("orderIds가 null이어도 NPE 없이 빈 결과를 반환한다(진입부 null 가드 존재)")
	void bulkShipOrders_nullOrderIds_returnsEmptyResultWithoutNpe() {
		BulkShipResult[] holder = new BulkShipResult[1];

		assertThatCode(() -> holder[0] = service.bulkShipOrders(null)).doesNotThrowAnyException();

		BulkShipResult result = holder[0];
		assertThat(result).isNotNull();
		assertThat(result.getSuccessCount()).isZero();
		assertThat(result.getFailedCount()).isZero();
		assertThat(result.getSkippedCount()).isZero();
		assertThat(result.getFailedIds()).isEmpty();
		assertThat(result.getErrors()).isNull();
		verifyNoInteractions(orderShipProcessor);
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class OrderShipTransactionBoundaryTest {
	@Mock
	private OrderShipProcessor orderShipProcessor;

	@Test
	@DisplayName("오케스트레이션 메서드 bulkShipOrders()는 @Transactional이 아니어야 한다(외부 발송을 긴 tx에 묶지 않도록)")
	void bulkShipOrdersIsNotTransactional() throws NoSuchMethodException {
		Method m = OrderShipService.class.getMethod("bulkShipOrders", List.class);
		assertThat(m.isAnnotationPresent(Transactional.class))
			.as("bulkShipOrders()는 외부 발송을 하나의 긴 트랜잭션에 묶지 않도록 @Transactional이 아니어야 한다")
			.isFalse();
	}

	@Test
	@DisplayName("주문 1건 발송 처리(shipSingleOrder)는 주문 단위로 독립 커밋되도록 @Transactional 이어야 한다")
	void processorMethodIsTransactional() throws NoSuchMethodException {
		Method m = OrderShipProcessor.class.getMethod("shipSingleOrder", Long.class);
		assertThat(m.isAnnotationPresent(Transactional.class))
			.as("주문 1건 발송은 주문 단위 독립 트랜잭션으로 커밋되어야 한다")
			.isTrue();
	}

	@Test
	@DisplayName("주문 수만큼 별도 트랜잭션 단위(processor)가 호출된다 — 한 주문 실패가 다른 주문을 롤백하지 않도록")
	void eachOrderIsProcessedInSeparateTransactionalUnit() {
		when(orderShipProcessor.shipSingleOrder(anyLong()))
			.thenReturn(OrderShipOutcome.shipped());

		BulkShipResult result = service().bulkShipOrders(List.of(1L, 2L, 3L));

		verify(orderShipProcessor, times(3)).shipSingleOrder(anyLong());
		assertThat(result.getSuccessCount()).isEqualTo(3);
		assertThat(result.getFailedCount()).isEqualTo(0);
	}

	@Test
	@DisplayName("한 주문의 발송 실패는 실패로 집계되지만 다른 주문 처리는 계속된다(독립 tx라 롤백 전파 없음)")
	void oneOrderFailureDoesNotStopOthers() {
		when(orderShipProcessor.shipSingleOrder(1L)).thenReturn(OrderShipOutcome.shipped());
		when(orderShipProcessor.shipSingleOrder(2L)).thenReturn(OrderShipOutcome.failed("마켓 전송 거부"));
		when(orderShipProcessor.shipSingleOrder(3L)).thenReturn(OrderShipOutcome.shipped());

		BulkShipResult result = service().bulkShipOrders(List.of(1L, 2L, 3L));

		assertThat(result.getSuccessCount()).isEqualTo(2);
		assertThat(result.getFailedCount()).isEqualTo(1);
		assertThat(result.getFailedIds()).containsExactly(2L);
		assertThat(result.getErrors()).isNotEmpty();
	}

	private OrderShipService service() {
		return new OrderShipService(orderShipProcessor);
	}
}

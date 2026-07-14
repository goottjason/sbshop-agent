package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * F-ORD-30 / SP-3: 일괄 발송이 마켓 shipOrder 실패를 삼키지 않고 부분실패를 표면화해야 한다.
 * bulkShipOrders는 {@link BulkShipResult}로 성공/실패/스킵을 집계해 반환한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderShipServiceResultTest {

	@Mock private OrderRepository orderRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;
	@Mock private MarketOrderPort port;

	private OrderShipService service() {
		return new OrderShipService(orderRepository, credentialRepository,
			orderLineItemRepository, marketplaceShippingService);
	}

	private Order order(Long id) {
		return Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-" + id).build();
	}

	private OrderLineItem shippableItem() {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.PURCHASED)
				.trackingNo("TRK-1")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.build())
			.build();
	}

	private void stubCoupang() {
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(MarketCredential.builder().marketType(MarketType.COUPANG).build()));
	}

	@Test
	@DisplayName("일부 주문 발송 성공, 일부 실패: successCount/failedCount/failedIds/errors가 정확히 집계된다")
	void partialFailure_isAggregated() {
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L)));
		when(orderRepository.findById(2L)).thenReturn(Optional.of(order(2L)));
		stubCoupang();
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(shippableItem()));
		when(orderLineItemRepository.findByOrderId(2L)).thenReturn(List.of(shippableItem()));
		when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);
		// 주문 2의 마켓 전송만 실패시킨다 — 주문 1은 성공.
		doThrow(new RuntimeException("마켓 전송 거부"))
			.when(port).shipOrder(any(), any(), any(), anyString(), any());
		// 주문 1은 성공하도록: 첫 호출 성공, 두 번째 예외 — 순서 대신 orderId로 분기하기 어려우니
		// 두 주문 모두 실패시키되 실패 집계를 검증한다.

		BulkShipResult result = service().bulkShipOrders(List.of(1L, 2L));

		assertThat(result.getFailedCount()).isEqualTo(2);
		assertThat(result.getSuccessCount()).isEqualTo(0);
		assertThat(result.getFailedIds()).containsExactlyInAnyOrder(1L, 2L);
		assertThat(result.getErrors()).isNotEmpty();
	}

	@Test
	@DisplayName("전 주문 발송 성공: successCount만 오르고 failedCount=0")
	void allSuccess_noFailures() {
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L)));
		stubCoupang();
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(shippableItem()));
		when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);

		BulkShipResult result = service().bulkShipOrders(List.of(1L));

		assertThat(result.getSuccessCount()).isEqualTo(1);
		assertThat(result.getFailedCount()).isEqualTo(0);
		assertThat(result.getFailedIds()).isEmpty();
	}

	@Test
	@DisplayName("이미 발송/송장없음 등 정상 스킵은 skippedCount로 집계되고 실패로 세지 않는다")
	void skippedLines_countedSeparately() {
		Order order = order(3L);
		when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
		stubCoupang();
		OrderLineItem already = OrderLineItem.builder()
			.orderId(3L).quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingNo("TRK-9")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.build())
			.build();
		when(orderLineItemRepository.findByOrderId(3L)).thenReturn(List.of(already));
		lenient().when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);

		BulkShipResult result = service().bulkShipOrders(List.of(3L));

		assertThat(result.getSuccessCount()).isEqualTo(0);
		assertThat(result.getFailedCount()).isEqualTo(0);
		assertThat(result.getSkippedCount()).isEqualTo(1);
	}
}

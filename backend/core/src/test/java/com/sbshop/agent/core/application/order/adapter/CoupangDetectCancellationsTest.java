package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangDetectCancellationsTest {
	@Mock
	private CoupangOrderApiPort coupangOrderApiPort;
	@Mock
	private CoupangStatusMapper statusMapper;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@InjectMocks
	private CoupangOrderAdapter adapter;

	@Test
	@DisplayName("[D-027] RETURNED 주문이 API 응답에 없어도 CANCELED로 덮어쓰지 않는다")
	void returnedOrder_notInApi_isNotCanceled() {
		Order returned = coupangOrder("R-1");
		OrderLineItem item = lineItemWithStatus(ShippingStatus.RETURNED);
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(returned));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

		adapter.detectCancellations(List.of(), LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("[D-027] EXCHANGED 주문이 API 응답에 없어도 CANCELED로 덮어쓰지 않는다")
	void exchangedOrder_notInApi_isNotCanceled() {
		Order exchanged = coupangOrder("E-1");
		OrderLineItem item = lineItemWithStatus(ShippingStatus.EXCHANGED);
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(exchanged));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

		adapter.detectCancellations(List.of(), LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("[D-027] 회귀 방지: NEW 주문이 API 응답에 없으면 CANCELED로 취소 처리된다")
	void newOrder_notInApi_isCanceled() {
		Order active = coupangOrder("N-1");
		OrderLineItem item = lineItemWithStatus(ShippingStatus.NEW);
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(active));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);

		adapter.detectCancellations(List.of(), LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("[D-027] 회귀 방지: DELIVERED 주문은 취소 처리되지 않는다")
	void deliveredOrder_notInApi_isNotCanceled() {
		Order delivered = coupangOrder("D-1");
		OrderLineItem item = lineItemWithStatus(ShippingStatus.DELIVERED);
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(delivered));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

		adapter.detectCancellations(List.of(), LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	private Order coupangOrder(String orderNo) {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo(orderNo)
			.orderDate(LocalDateTime.now().minusDays(1))
			.build();
	}

	private OrderLineItem lineItemWithStatus(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCommandRefreshTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;
	@Mock
	private LineItemShippingWriter shippingWriter;
	@Mock
	private OrderMarketRefresher marketRefresher;

	private OrderService service;

	@BeforeEach
	void setUp() {
		service = new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService, shippingWriter, marketRefresher);
	}

	private Order order(Long id) {
		Order o = Order.builder().marketType(MarketType.COUPANG)
			.marketOrderNo("ORD-" + id)
			.orderDate(LocalDateTime.now()).build();
		ReflectionTestUtils.setField(o, "id", id);
		return o;
	}

	private OrderLineItem item(Long id, ShippingStatus status) {
		OrderLineItem li = OrderLineItem.builder().orderId(1L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build()).build();
		ReflectionTestUtils.setField(li, "id", id);
		return li;
	}

	@Test
	@DisplayName("주문확인은 상태를 스스로 찍지 않고 마켓에 다시 물어본다 — 마켓이 진실이다")
	void confirmDoesNotGuessStatus() {
		Order o = order(1L);
		OrderLineItem li = item(10L, ShippingStatus.NEW);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(credentialRepository.findByMarketType(any())).thenReturn(Optional.empty());
		when(marketplaceShippingService.getPort(any()))
			.thenReturn(mock(com.sbshop.agent.core.application.order.port.MarketOrderPort.class));

		service.confirmOrder(1L);

		assertThat(li.getShippingData().getShippingStatus())
			.as("로컬로 PREPARING 을 찍으면 안 된다")
			.isEqualTo(ShippingStatus.NEW);
		verify(marketRefresher).refreshOne(o);
	}

	@Test
	@DisplayName("발송처리는 마켓에 먼저 보내고 그 결과를 다시 읽는다 — 로컬을 먼저 찍지 않는다")
	void shipSendsToMarketBeforeTouchingLocal() {
		OrderLineItem li = item(10L, ShippingStatus.PREPARING);
		Order o = order(1L);
		when(orderLineItemRepository.findById(10L)).thenReturn(Optional.of(li));
		when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(new MarketShippingResult(true, false, false, null));

		service.processShipping(10L, "123456789", ShippingCarrier.CJ_LOGISTICS);

		assertThat(li.getShippingData().getShippingStatus())
			.as("마켓 응답을 보기 전에 DISPATCHED 를 찍으면 안 된다")
			.isEqualTo(ShippingStatus.PREPARING);
		verify(marketRefresher).refreshOne(o);
	}

	@Test
	@DisplayName("일괄 주문확인은 건마다 단건 조회하지 않고 목록으로 한 번에 갱신한다")
	void bulkConfirmRefreshesByList() {
		Order o = order(1L);
		OrderLineItem li = item(10L, ShippingStatus.NEW);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(li));
		when(credentialRepository.findByMarketType(any())).thenReturn(Optional.empty());
		when(marketplaceShippingService.getPort(any()))
			.thenReturn(mock(com.sbshop.agent.core.application.order.port.MarketOrderPort.class));

		service.bulkConfirmOrders(List.of(1L));

		verify(marketRefresher).refreshAfterBulk(java.util.Set.of(MarketType.COUPANG));
		verify(marketRefresher, org.mockito.Mockito.never()).refreshOne(any());
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceCancelPropagationTest {
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	@Test
	@DisplayName("GMARKET 주문 취소 → cancelOrderToMarketplace 호출됨")
	void gmarketCancel_propagatesToMarketplace() {
		Order order = orderOf(MarketType.GMARKET);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(1L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
	}

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;

	@Test
	@DisplayName("AUCTION 주문 취소 → cancelOrderToMarketplace 호출됨")
	void auctionCancel_propagatesToMarketplace() {
		Order order = orderOf(MarketType.AUCTION);
		when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(2L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
	}

	@Test
	@DisplayName("D-272: COUPANG 주문 취소도 마켓에 전파한다 — 우리 장부에만 있는 취소를 없앤다")
	void coupangCancel_propagatesToMarket() {
		Order order = orderOf(MarketType.COUPANG);
		when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(3L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
	}

	@Test
	@DisplayName("D-272: SMART_STORE 주문 취소도 마켓에 전파한다")
	void smartStoreCancel_propagatesToMarket() {
		Order order = orderOf(MarketType.SMART_STORE);
		when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(5L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
	}

	@Test
	@DisplayName("D-272: 쿠팡·스토어는 마켓 전송 후 로컬에도 취소를 기록한다 — 재조회가 취소를 싣는지 검증되지 않았다")
	void coupangCancel_alsoWritesLocally() {
		Order order = orderOf(MarketType.COUPANG);
		OrderLineItem item = newItem();
		when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service().cancelOrder(7L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("D-272: G마켓·옥션은 로컬에 쓰지 않는다 — 재조회가 진실을 싣는 검증된 경로다")
	void gmarketCancel_leavesLocalToRefetch() {
		Order order = orderOf(MarketType.GMARKET);
		OrderLineItem item = newItem();
		when(orderRepository.findById(8L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

		service().cancelOrder(8L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
	}

	private OrderLineItem newItem() {
		return OrderLineItem.builder().orderId(1L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.NEW).build())
			.build();
	}

	@Test
	@DisplayName("D-272: 11번가는 전파하지 않는다 — 판매자 취소 API 가 없다(어댑터가 UnsupportedOperation 을 던진다)")
	void elevenstCancel_doesNotPropagate() {
		Order order = orderOf(MarketType.ELEVEN_STREET);
		when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(6L);

		verify(marketplaceShippingService, never()).cancelOrderToMarketplace(order);
	}

	@Test
	@DisplayName("GMARKET 마켓전파 실패 → RuntimeException 전파")
	void gmarketCancelFails_throwsRuntimeException() {
		Order order = orderOf(MarketType.GMARKET);
		when(orderRepository.findById(4L)).thenReturn(Optional.of(order));
		lenient().when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());
		doThrow(new RuntimeException("G마켓 취소 API 오류"))
			.when(marketplaceShippingService).cancelOrderToMarketplace(order);

		assertThatThrownBy(() -> service().cancelOrder(4L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("마켓 주문취소 실패");
	}

	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService, shippingWriter(), orderMarketRefresher());
	}

	private Order orderOf(MarketType marketType) {
		return Order.builder()
			.marketType(marketType)
			.marketOrderNo("ORD-" + marketType.name())
			.build();
	}
	private com.sbshop.agent.core.application.order.service.OrderMarketRefresher orderMarketRefresher() {
		return org.mockito.Mockito.mock(
			com.sbshop.agent.core.application.order.service.OrderMarketRefresher.class);
	}

}

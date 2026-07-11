package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;

/**
 * SP-E Task 4: cancelOrder에서 G마켓/옥션 주문은 Cafe24로 취소가 전파되고,
 * 그 외 마켓(쿠팡 등)은 로컬-only(현행 유지)임을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceCancelPropagationTest {

	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService);
	}

	private Order orderOf(MarketType marketType) {
		return Order.builder()
			.marketType(marketType)
			.marketOrderNo("ORD-" + marketType.name())
			.build();
	}

	@Test
	@DisplayName("GMARKET 주문 취소 → cancelOrderToMarketplace 호출됨")
	void gmarketCancel_propagatesToMarketplace() {
		Order order = orderOf(MarketType.GMARKET);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(1L);

		verify(marketplaceShippingService).cancelOrderToMarketplace(order);
	}

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
	@DisplayName("COUPANG 주문 취소 → cancelOrderToMarketplace 호출 안 됨 (회귀 불변)")
	void coupangCancel_doesNotPropagateToMarketplace() {
		Order order = orderOf(MarketType.COUPANG);
		when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		service().cancelOrder(3L);

		verify(marketplaceShippingService, never()).cancelOrderToMarketplace(order);
	}

	@Test
	@DisplayName("GMARKET 마켓전파 실패 → RuntimeException 전파")
	void gmarketCancelFails_throwsRuntimeException() {
		Order order = orderOf(MarketType.GMARKET);
		when(orderRepository.findById(4L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());
		doThrow(new RuntimeException("G마켓 취소 API 오류"))
			.when(marketplaceShippingService).cancelOrderToMarketplace(order);

		assertThatThrownBy(() -> service().cancelOrder(4L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("마켓 주문취소 실패");
	}
}

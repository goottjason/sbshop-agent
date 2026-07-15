package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
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
 * 저순위 order 입력검증·엣지케이스 가드.
 * - F-H4: PURCHASED→SHIPPED 전이 시 trackingNo(공백 포함) 필수.
 * - F-ORD-22: 라인아이템 없는 주문의 발주확인 차단.
 * - F-ORD-26: isUnipassDone null(빈 요청) 차단 — no-op 성공 방지.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceInputGuardTest {

	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService);
	}

	private OrderLineItem itemWithStatus(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	// ===================== F-H4 =====================

	@Test
	@DisplayName("F-H4: PURCHASED→SHIPPED 전이 시 trackingNo 없으면 차단, 마켓 전송·저장 없음")
	void purchasedToShipped_withoutTrackingNo_blocked() {
		OrderLineItem item = itemWithStatus(ShippingStatus.PURCHASED);
		when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));

		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.build();

		assertThatThrownBy(() -> service().updateShippingInfo(1L, cmd))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("송장번호");

		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
		verify(orderLineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("F-H4: PURCHASED→SHIPPED 전이 시 trackingNo가 공백이면 차단")
	void purchasedToShipped_withBlankTrackingNo_blocked() {
		OrderLineItem item = itemWithStatus(ShippingStatus.PURCHASED);
		when(orderLineItemRepository.findById(2L)).thenReturn(Optional.of(item));

		ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
			.trackingNo("   ")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.build();

		assertThatThrownBy(() -> service().updateShippingInfo(2L, cmd))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("송장번호");

		verify(orderLineItemRepository, never()).save(any());
	}

	// ===================== F-ORD-22 =====================

	@Test
	@DisplayName("F-ORD-22: 라인아이템이 하나도 없는 주문의 발주확인 차단(마켓 API 호출 없음)")
	void confirmOrder_withNoLineItems_blocked() {
		Order order = Order.builder().marketType(MarketType.COUPANG).build();
		when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());

		assertThatThrownBy(() -> service().confirmOrder(5L))
			.isInstanceOf(IllegalStateException.class);

		verify(marketplaceShippingService, never()).getPort(any());
	}

	// ===================== F-ORD-26 =====================

	@Test
	@DisplayName("F-ORD-26: isUnipassDone null(빈 요청) → 차단, no-op 성공 방지")
	void updateOrderLineItem_withNullUnipassDone_blocked() {
		OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
		when(orderLineItemRepository.findById(3L)).thenReturn(Optional.of(item));

		OrderLineItemUpdateCommand cmd = OrderLineItemUpdateCommand.builder().build();

		assertThatThrownBy(() -> service().updateOrderLineItem(3L, cmd))
			.isInstanceOf(IllegalArgumentException.class);

		verify(orderLineItemRepository, never()).save(any());
	}
}

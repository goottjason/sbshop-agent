package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
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
 * SP-4 / F-ORD-29: 발송 진입 상태 가드 — 이미 발송(SHIPPED)·배송완료(DELIVERED)·종료 상태의
 * 라인아이템은 재발송하지 않는다(스킵). F-ORD-31로 주문 1건 발송 로직이 {@link OrderShipProcessor}로
 * 이동했으므로 가드 검증도 여기서 수행한다(로직 불변).
 */
@ExtendWith(MockitoExtension.class)
class OrderShipServiceGuardTest {

	@Mock private OrderRepository orderRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;
	@Mock private MarketOrderPort port;

	private OrderShipProcessor processor() {
		return new OrderShipProcessor(orderRepository, credentialRepository,
			orderLineItemRepository, marketplaceShippingService);
	}

	private OrderLineItem itemWith(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(status)
				.trackingNo("TRK-1")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.build())
			.build();
	}

	@Test
	@DisplayName("이미 SHIPPED인 라인아이템은 재발송하지 않는다(port.shipOrder 미호출)")
	void shippedItem_notReshipped() {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-1").build();
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(MarketCredential.builder().marketType(MarketType.COUPANG).build()));
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(itemWith(ShippingStatus.SHIPPED)));
		// 가드가 없으면 현행 코드는 trackingNo가 있는 SHIPPED 라인을 재발송하려 port.shipOrder를 호출한다 —
		// getPort를 실 mock에 연결해 그 호출을 관측 가능하게 한다(가드가 있으면 애초에 getPort조차 안 탄다).
		lenient().when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);

		processor().shipSingleOrder(1L);

		verify(port, never()).shipOrder(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("PURCHASED 라인아이템은 발송한다(port.shipOrder 호출)")
	void purchasedItem_shipped() {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-2").build();
		OrderLineItem item = itemWith(ShippingStatus.PURCHASED);
		MarketCredential cred = MarketCredential.builder().marketType(MarketType.COUPANG).build();
		when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.of(cred));
		when(orderLineItemRepository.findByOrderId(2L)).thenReturn(List.of(item));
		when(marketplaceShippingService.getPort(MarketType.COUPANG)).thenReturn(port);

		processor().shipSingleOrder(2L);

		verify(port).shipOrder(same(cred), same(order), same(item), eq("TRK-1"), eq(ShippingCarrier.CJ_LOGISTICS));
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.adapter.ElevenstOrderAdapter;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-028 회귀 방지: 11번가 postSyncProcess가 취소 감지를 하지 않아, 취소된 주문이 이전 상태로
 * 영구 잔류하던 결함을 고정한다. 쿠팡 정본 패턴(terminal = CANCELED·DELIVERED·RETURNED·EXCHANGED)을
 * 이식하여, API 응답에 없는 non-terminal 주문만 CANCELED 처리한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElevenstDetectCancellationsTest {

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ElevenstOrderAdapter elevenstOrderAdapter;

	private ElevenstOrderSyncService service() {
		return new ElevenstOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository,
			productRepository, eventPublisher, elevenstOrderAdapter);
	}

	private void stubCredentialAndEmptyApi() {
		MarketCredential credential = org.mockito.Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		when(credentialRepository.findByMarketType(MarketType.ELEVEN_STREET))
			.thenReturn(java.util.Optional.of(credential));
		// API 응답 비어 있음 → 모든 DB 주문이 조회 대상에 없는 상황
		when(elevenstOrderAdapter.fetchOrders(any(), any(), any())).thenReturn(List.of());
	}

	private Order order(String orderNo) {
		return Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo(orderNo)
			.orderDate(LocalDateTime.now().minusDays(1))
			.build();
	}

	private OrderLineItem item(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	@Test
	@DisplayName("[D-028] NEW 주문이 API 응답에서 사라지면 CANCELED로 처리된다")
	void newOrder_absentFromApi_isCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.NEW);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("A-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("[D-028] RETURNED 주문은 API 응답에 없어도 CANCELED로 오취소하지 않는다")
	void returnedOrder_absentFromApi_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.RETURNED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("R-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}

	@Test
	@DisplayName("[D-028] EXCHANGED 주문은 API 응답에 없어도 CANCELED로 오취소하지 않는다")
	void exchangedOrder_absentFromApi_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.EXCHANGED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("E-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("[D-028] DELIVERED 주문은 취소 처리되지 않는다")
	void deliveredOrder_absentFromApi_isNotCanceled() {
		stubCredentialAndEmptyApi();
		OrderLineItem li = item(ShippingStatus.DELIVERED);
		when(orderRepository.findByMarketType(MarketType.ELEVEN_STREET)).thenReturn(List.of(order("D-1")));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(li));

		service().syncElevenstOrders();

		assertThat(li.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}
}

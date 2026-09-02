package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
class TrackingInputWindowTest {

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
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(new MarketShippingResult(true, false, false, null));
	}

	private void givenItem(ShippingStatus status) {
		OrderLineItem li = OrderLineItem.builder().orderId(1L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build()).build();
		ReflectionTestUtils.setField(li, "id", 10L);
		Order o = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("ORD-1")
			.orderDate(LocalDateTime.now()).build();
		ReflectionTestUtils.setField(o, "id", 1L);
		when(orderLineItemRepository.findById(10L)).thenReturn(Optional.of(li));
		when(orderRepository.findById(1L)).thenReturn(Optional.of(o));
	}

	@Test
	@DisplayName("구매준비 상태에서만 송장을 수정할 수 있다 — 유일한 입력 시점이다")
	void preparingAllowsTrackingEdit() {
		givenItem(ShippingStatus.PREPARING);

		assertThatCode(() -> service.updateTrackingInfo(10L, "123", ShippingCarrier.CJ_LOGISTICS))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("결제완료 상태에서는 송장을 수정할 수 없다 — 주문확인을 거쳐야 한다")
	void newBlocksTrackingEdit() {
		givenItem(ShippingStatus.NEW);

		assertThatThrownBy(() -> service.updateTrackingInfo(10L, "123", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PREPARING");
	}

	@Test
	@DisplayName("발송 뒤에는 송장을 수정할 수 없다 — 대부분의 마켓이 수정 API 를 주지 않는다")
	void dispatchedBlocksTrackingEdit() {
		givenItem(ShippingStatus.DISPATCHED);

		assertThatThrownBy(() -> service.updateTrackingInfo(10L, "123", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("배송중에도 송장을 수정할 수 없다")
	void shippedBlocksTrackingEdit() {
		givenItem(ShippingStatus.SHIPPED);

		assertThatThrownBy(() -> service.updateTrackingInfo(10L, "123", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalStateException.class);
	}
}

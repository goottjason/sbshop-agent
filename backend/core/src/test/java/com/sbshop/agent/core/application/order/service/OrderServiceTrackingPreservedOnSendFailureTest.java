package com.sbshop.agent.core.application.order.service;

import org.assertj.core.api.Assertions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTrackingPreservedOnSendFailureTest {
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	@Test
	@DisplayName("D-125: 일시 전송 실패여도 로컬 송장은 저장되고 예외를 던지지 않는다")
	void temporaryFailure_preservesLocalTracking() {
		OrderLineItem item = shippedItem();
		stubOrder(MarketType.GMARKET);
		when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofFailed("Cafe24 API POST 호출 실패"));

		assertThatCode(() -> service().updateShippingInfo(1L, command())).doesNotThrowAnyException();

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("6079990333504");
		assertThat(item.getShippingData().getShippingCarrier()).isEqualTo(ShippingCarrier.KOREA_POST);
		assertThat(item.getShippingData().getTrackingSentToMarket()).isNotEqualTo(Boolean.TRUE);
		verify(orderLineItemRepository).save(same(item));
	}

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;

	@Test
	@DisplayName("D-125: 영구 거부(terminal)여도 로컬 송장은 저장된다 — 마켓이 안 받아줄 뿐 송장은 실재한다")
	void terminalRejection_preservesLocalTracking() {
		OrderLineItem item = shippedItem();
		stubOrder(MarketType.GMARKET);
		when(orderLineItemRepository.findById(2L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofTerminal("You cannot change to that order state"));

		assertThatCode(() -> service().updateShippingInfo(2L, command())).doesNotThrowAnyException();

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("6079990333504");
		verify(orderLineItemRepository).save(same(item));
	}

	@Test
	@DisplayName("D-125 회귀: 전송 성공이면 종전대로 trackingSentToMarket이 마킹된다")
	void success_marksSentToMarket() {
		OrderLineItem item = shippedItem();
		stubOrder(MarketType.COUPANG);
		when(orderLineItemRepository.findById(3L)).thenReturn(Optional.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofSent());

		service().updateShippingInfo(3L, command());

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("6079990333504");
		assertThat(item.getShippingData().getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("D-125 회귀: 종료상태(취소/반품) 차단은 그대로 유지된다")
	void terminalOrderStatus_stillBlocked() {
		OrderLineItem canceled = OrderLineItem.builder()
			.orderId(223L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.CANCELED).build())
			.build();
		when(orderLineItemRepository.findById(4L)).thenReturn(Optional.of(canceled));

		Assertions
			.assertThatThrownBy(() -> service().updateShippingInfo(4L, command()))
			.isInstanceOf(IllegalStateException.class);
	}

	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService, shippingWriter());
	}

	private ShippingUpdateCommand command() {
		return ShippingUpdateCommand.builder()
			.trackingNo("6079990333504")
			.shippingCarrier(ShippingCarrier.KOREA_POST)
			.build();
	}

	private OrderLineItem shippedItem() {
		return OrderLineItem.builder()
			.orderId(223L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
	}

	private void stubOrder(MarketType marketType) {
		lenient().when(orderRepository.findById(223L))
			.thenReturn(Optional.of(Order.builder().marketType(marketType).build()));
	}
}

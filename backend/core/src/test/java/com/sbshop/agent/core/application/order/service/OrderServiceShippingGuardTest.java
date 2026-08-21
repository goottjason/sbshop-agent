package com.sbshop.agent.core.application.order.service;

import org.assertj.core.api.Assertions;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

@ExtendWith(MockitoExtension.class)
class OrderServiceShippingGuardTest {
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	@Test
	@DisplayName("종료상태(CANCELED) 송장수정 → 차단(IllegalStateException), 마켓 전송·로컬 저장 없음")
	void canceledItem_shippingUpdate_blocked() {
		OrderLineItem item = itemWithStatus(ShippingStatus.CANCELED);
		when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));

		assertThatThrownBy(() -> service().updateShippingInfo(1L, command()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("전송 대상");

		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(same(item), anyBoolean());
		verify(orderLineItemRepository, never()).save(same(item));
	}

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;

	@Test
	@DisplayName("종료상태(RETURNED) 송장수정 → 차단(IllegalStateException)")
	void returnedItem_shippingUpdate_blocked() {
		OrderLineItem item = itemWithStatus(ShippingStatus.RETURNED);
		when(orderLineItemRepository.findById(2L)).thenReturn(Optional.of(item));

		assertThatThrownBy(() -> service().updateShippingInfo(2L, command()))
			.isInstanceOf(IllegalStateException.class);

		verify(orderLineItemRepository, never()).save(same(item));
	}

	@Test
	@DisplayName("D-125 계약 변경: SHIPPED 송장수정 → 마켓 terminal이어도 로컬 송장은 보존(롤백 없음)")
	void marketTerminal_preservesLocalTracking() {
		OrderLineItem item = itemWithStatus(ShippingStatus.SHIPPED);
		when(orderLineItemRepository.findById(3L)).thenReturn(Optional.of(item));
		lenient().when(orderRepository.findById(10L))
			.thenReturn(Optional.of(Order.builder().marketType(MarketType.COUPANG).build()));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofTerminal("배송진행상태가 유효하지 않습니다"));

		Assertions
			.assertThatCode(() -> service().updateShippingInfo(3L, command()))
			.doesNotThrowAnyException();

		Assertions.assertThat(item.getShippingData().getTrackingNo())
			.isEqualTo("123456789");
		Assertions.assertThat(item.getShippingData().getTrackingSentToMarket())
			.isNotEqualTo(Boolean.TRUE);
		verify(orderLineItemRepository).save(same(item));
	}

	@Test
	@DisplayName("PREPARING + trackingNo 있으면 → DISPATCHED 전이 성공 (차단 없음)")
	void preparing_with_trackingNo_proceeds() {
		OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
		when(orderLineItemRepository.findById(4L)).thenReturn(Optional.of(item));
		lenient().when(orderRepository.findById(10L))
			.thenReturn(Optional.of(Order.builder().marketType(MarketType.COUPANG).build()));
		lenient().when(credentialRepository.findByMarketType(any())).thenReturn(Optional.empty());
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofSkipped("test"));

		assertThatCode(() -> service().updateShippingInfo(4L, command())).doesNotThrowAnyException();
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
			.trackingNo("123456789")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.build();
	}

	private OrderLineItem itemWithStatus(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

@ExtendWith(MockitoExtension.class)
class OrderServiceClearFieldsTest {
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;

	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService, shippingWriter(), orderMarketRefresher());
	}

	private OrderLineItem progressedItem() {
		return OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
	}

	private Order orderWith(String address, String customsNo) {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("O-1")
			.address(address)
			.customsData(CustomsData.builder().customsClearanceNo(customsNo).build())
			.build();
	}

	@Nested
	class ClearWithEmptyString {
		@Test
		@DisplayName("빈 문자열 주소 → 주소가 빈 값으로 클리어된다")
		void emptyAddress_clearsAddress() {
			Order order = orderWith("서울시 강남구 ...", "P1234567890123");
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

			Order result = service().updateOrder(1L,
				OrderUpdateCommand.builder().address("").build());

			assertThat(result.getAddress()).isEmpty();
		}

		@Test
		@DisplayName("빈 문자열 통관번호 → 통관번호가 빈 값으로 클리어된다")
		void emptyCustomsNo_clearsCustomsClearanceNo() {
			Order order = orderWith("서울시 강남구 ...", "P1234567890123");
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

			Order result = service().updateOrder(1L,
				OrderUpdateCommand.builder().customsClearanceNo("").build());

			assertThat(result.getCustomsData().getCustomsClearanceNo()).isEmpty();
		}
	}

	@Nested
	class NullKeepsExisting {
		@Test
		@DisplayName("주소 null(미전송) → 기존 주소 유지")
		void nullAddress_keepsExisting() {
			Order order = orderWith("서울시 강남구 ...", "P1234567890123");
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

			Order result = service().updateOrder(1L,
				OrderUpdateCommand.builder().customsClearanceNo("P9999999999999").build());

			assertThat(result.getAddress()).isEqualTo("서울시 강남구 ...");
		}

		@Test
		@DisplayName("통관번호 null(미전송) → 기존 통관번호 유지")
		void nullCustomsNo_keepsExisting() {
			Order order = orderWith("서울시 강남구 ...", "P1234567890123");
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

			Order result = service().updateOrder(1L,
				OrderUpdateCommand.builder().address("부산시 해운대구 ...").build());

			assertThat(result.getCustomsData().getCustomsClearanceNo()).isEqualTo("P1234567890123");
		}
	}

	@Nested
	class AllNewProtectionPreserved {
		@Test
		@DisplayName("all-NEW 상태에서 빈 문자열 클리어 시도 → 차단(기존 주소보호 유지)")
		void allNew_clearBlocked() {
			Order order = orderWith("서울시 강남구 ...", "P1234567890123");
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			OrderLineItem newItem = OrderLineItem.builder()
				.orderId(10L).quantity(1)
				.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.NEW).build())
				.build();
			when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(newItem));

			assertThatThrownBy(() -> service().updateOrder(1L,
				OrderUpdateCommand.builder().address("").customsClearanceNo("").build()))
				.isInstanceOf(IllegalStateException.class);

			assertThat(order.getAddress()).isEqualTo("서울시 강남구 ...");
			assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P1234567890123");
		}
	}
	private com.sbshop.agent.core.application.order.service.OrderMarketRefresher orderMarketRefresher() {
		return org.mockito.Mockito.mock(
			com.sbshop.agent.core.application.order.service.OrderMarketRefresher.class);
	}

}

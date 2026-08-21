package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.dto.OrderLineItemUpdateCommand;
import com.sbshop.agent.core.application.order.dto.SourcingUpdateCommand;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

@ExtendWith(MockitoExtension.class)
class OrderServiceStateGuardTest {
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
			credentialRepository, marketplaceShippingService, shippingWriter());
	}

	private OrderLineItem itemWithStatus(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	@Nested
	class UnipassFreeUpdate {
		@Test
		@DisplayName("NEW 라인아이템 유니패스 수정 성공 (상태 가드 제거)")
		void newItem_unipassUpdate_succeeds() {
			OrderLineItem item = itemWithStatus(ShippingStatus.NEW);
			when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			OrderLineItem result = service().updateOrderLineItem(1L,
				OrderLineItemUpdateCommand.builder().isUnipassDone(true).build());

			assertThat(result.getIsUnipassDone()).isTrue();
		}

		@Test
		@DisplayName("CANCELED 라인아이템 유니패스 수정 성공 (종료상태도 허용)")
		void canceledItem_unipassUpdate_succeeds() {
			OrderLineItem item = itemWithStatus(ShippingStatus.CANCELED);
			when(orderLineItemRepository.findById(2L)).thenReturn(Optional.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			OrderLineItem result = service().updateOrderLineItem(2L,
				OrderLineItemUpdateCommand.builder().isUnipassDone(true).build());

			assertThat(result.getIsUnipassDone()).isTrue();
		}
	}

	@Nested
	class SourcingClear {
		@Test
		@DisplayName("종료상태(EXCHANGED) 소싱 discountCode 빈 문자열 클리어 가능")
		void endStateItem_clearsDiscountCode_withEmptyString() {
			OrderLineItem item = OrderLineItem.builder()
				.orderId(10L)
				.quantity(1)
				.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.EXCHANGED).build())
				.sourcingData(SourcingData.builder().discountCode("SAVE10").build())
				.build();
			when(orderLineItemRepository.findById(3L)).thenReturn(Optional.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			OrderLineItem result = service().updateSourcingInfo(3L,
				SourcingUpdateCommand.builder().discountCode("").build());

			assertThat(result.getSourcingData().getDiscountCode()).isEmpty();
		}

		@Test
		@DisplayName("PREPARING 소싱수정(주문번호 있음) → purchaseStatus 자동변경 없음(NOT_PURCHASED 유지), shippingStatus 유지")
		void preparing_sourcing_update_with_orderNo_keeps_purchaseStatus() {
			OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
			when(orderLineItemRepository.findById(4L)).thenReturn(Optional.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			OrderLineItem result = service().updateSourcingInfo(4L,
				SourcingUpdateCommand.builder().sourcingOrderNo("ORD-99").sourcingVendor("V1").build());

			assertThat(result.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
			assertThat(result.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
			assertThat(result.getSourcingData().getSourcingOrderNo()).isEqualTo("ORD-99");
			verify(orderLineItemRepository).save(item);
		}

		@Test
		@DisplayName("PREPARING 소싱수정(주문번호 없음) → 저장 허용, purchaseStatus=NOT_PURCHASED 유지")
		void preparingItem_sourcingUpdateWithoutOrderNo_allowed() {
			OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
			when(orderLineItemRepository.findById(5L)).thenReturn(Optional.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			OrderLineItem result = service().updateSourcingInfo(5L,
				SourcingUpdateCommand.builder().sourcingVendor("V1").build());

			assertThat(result.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
			assertThat(result.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
			assertThat(result.getSourcingData().getSourcingVendor()).isEqualTo("V1");
			verify(orderLineItemRepository).save(item);
		}
	}

	@Nested
	class CancelOrderNewOnly {
		@Test
		@DisplayName("NEW 주문 취소 → 성공 (라인아이템 CANCELED 전이)")
		void newOrder_cancel_succeeds() {
			Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-1").build();
			OrderLineItem item = itemWithStatus(ShippingStatus.NEW);
			when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

			service().cancelOrder(1L);

			assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.CANCELED);
		}

		@Test
		@DisplayName("DISPATCHED 주문 취소 → 차단(IllegalStateException)")
		void dispatched_cancel_blocked() {
			Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-2").build();
			OrderLineItem item = itemWithStatus(ShippingStatus.DISPATCHED);
			when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

			assertThatThrownBy(() -> service().cancelOrder(2L))
				.isInstanceOf(IllegalStateException.class);
			verify(orderLineItemRepository, never()).save(any());
		}

		@Test
		@DisplayName("SHIPPED 주문 취소 → 차단(IllegalStateException)")
		void shippedOrder_cancel_blocked() {
			Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-3").build();
			OrderLineItem item = itemWithStatus(ShippingStatus.SHIPPED);
			when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

			assertThatThrownBy(() -> service().cancelOrder(3L))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	class ConfirmOrderReentryGuard {
		@Test
		@DisplayName("이미 SHIPPED 진행 상태 주문 재확인 → 차단(IllegalStateException)")
		void shippedOrder_reconfirm_blocked() {
			Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-4").build();
			OrderLineItem item = itemWithStatus(ShippingStatus.SHIPPED);
			when(orderRepository.findById(4L)).thenReturn(Optional.of(order));
			lenient().when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

			assertThatThrownBy(() -> service().confirmOrder(4L))
				.isInstanceOf(IllegalStateException.class);
		}

		@Test
		@DisplayName("종료상태(CANCELED) 주문 재확인 → 차단(IllegalStateException)")
		void canceledOrder_reconfirm_blocked() {
			Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("O-5").build();
			OrderLineItem item = itemWithStatus(ShippingStatus.CANCELED);
			when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
			lenient().when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));

			assertThatThrownBy(() -> service().confirmOrder(5L))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Nested
	class ConfirmOrderCafe24NoCredential {
		@Test
		@DisplayName("AUCTION 주문 발주확인 → 마켓 크레덴셜 없어도 성공(NEW→PREPARING), 포트 위임")
		void auctionOrder_confirm_withoutCredential_succeeds() {
			Order order = Order.builder().marketType(MarketType.AUCTION).marketOrderNo("2566278285").build();
			OrderLineItem item = itemWithStatus(ShippingStatus.NEW);
			when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
			when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
			when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
			when(credentialRepository.findByMarketType(MarketType.AUCTION)).thenReturn(Optional.empty());
			MarketOrderPort port = Mockito
				.mock(MarketOrderPort.class);
			when(marketplaceShippingService.getPort(MarketType.AUCTION)).thenReturn(port);

			service().confirmOrder(6L);

			verify(port).acceptOrders(ArgumentMatchers.isNull(), ArgumentMatchers.eq(order));
			assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
		}
	}
}

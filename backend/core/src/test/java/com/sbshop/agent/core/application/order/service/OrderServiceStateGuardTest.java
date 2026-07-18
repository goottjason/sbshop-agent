package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

/**
 * SP-4 상태가드 (데이터 주인 기준):
 * - 유니패스 수정은 관리용이므로 상태 무관 자유 허용 (F-ORD-25 종결)
 * - 소싱 필드는 종료상태에서도 수정 허용(현행 유지)하며 빈 값 클리어 가능 (F-S2, F-S1 불변)
 * - 발주취소는 NEW 상태에서만 허용 (F-ORD-13)
 * - 발주확인은 진행/종료 상태 재실행 차단 (F-ORD-6)
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceStateGuardTest {

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

	// ==================== 2-1. 유니패스 자유 수정 (F-ORD-25) ====================

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

	// ==================== 2-2. 소싱정보 클리어 가능 (F-S2) ====================

	@Nested
	class SourcingClear {

		/** END(EXCHANGED) 상태 라인아이템의 discountCode를 빈 값으로 클리어. */
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

		/** 구매상태 수동 관리: PREPARING에 주문번호로 소싱수정해도 purchaseStatus는 자동 변경되지 않는다.
		 *  (구매상태는 유저가 그리드 셀렉트로 수동 관리. 이메일 페치는 주문번호 유무로 대상 판단.) */
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

		/** 가드 완화: PREPARING이고 주문번호가 없어도 소싱수정은 저장된다.
		 *  주문번호가 없으므로 자동 구매완료 처리는 하지 않고 purchaseStatus는 NOT_PURCHASED로 유지된다. */
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

	// ==================== 2-4. 발주취소 NEW-only (F-ORD-13) ====================

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

	// ==================== 2-5. 발주확인 재실행 가드 (F-ORD-6) ====================

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
}

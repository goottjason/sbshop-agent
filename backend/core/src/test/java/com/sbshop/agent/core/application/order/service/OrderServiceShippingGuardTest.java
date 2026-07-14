package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * SP-4 / F-H1·F-H2: 송장은 마켓이 진실 원본이다.
 * - 종료(CANCELED/RETURNED/EXCHANGED) 상태의 라인아이템은 송장 수정 대상이 아니므로
 *   로컬 저장도 하지 않고 차단한다.
 * - 마켓 전송 결과가 terminal(영구 잠금)이면 일시 실패(failed)와 구분되는 전용 메시지로 롤백한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceShippingGuardTest {

	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService);
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
	@DisplayName("SHIPPED 송장수정 → 마켓 terminal → 전용 메시지로 롤백(failed와 구분)")
	void marketTerminal_throwsWithDedicatedMessage() {
		OrderLineItem item = itemWithStatus(ShippingStatus.SHIPPED);
		when(orderLineItemRepository.findById(3L)).thenReturn(Optional.of(item));
		lenient().when(orderRepository.findById(10L))
			.thenReturn(Optional.of(Order.builder().marketType(MarketType.COUPANG).build()));
		when(marketplaceShippingService.sendTrackingToMarketplace(same(item), anyBoolean()))
			.thenReturn(MarketShippingResult.ofTerminal("배송진행상태가 유효하지 않습니다"));

		assertThatThrownBy(() -> service().updateShippingInfo(3L, command()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("동기화");
	}
}

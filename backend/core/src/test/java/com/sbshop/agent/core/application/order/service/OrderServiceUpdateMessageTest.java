package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.dto.OrderUpdateCommand;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * 배송메시지 수동 수정. 주소와 같은 시맨틱을 따른다 —
 * 빈 문자열("")은 클리어, null(미전송)은 변경 안 함.
 *
 * <p>동기화 경로({@link Order#update})의 "빈값 거부" 가드와 헷갈리지 말 것:
 * 그 가드는 마켓이 빈 메시지를 보내와 사용자가 적어둔 요청사항을 지우는 것을 막기 위한 것이고,
 * 이 수동 경로는 사용자가 <b>의도적으로</b> 지우는 것이라 빈값이 통과해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceUpdateMessageTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketplaceShippingService marketplaceShippingService;

	private OrderService service() {
		return new OrderService(orderRepository, orderLineItemRepository,
			credentialRepository, marketplaceShippingService,
			new LineItemShippingWriter(shipmentRepository, orderLineItemRepository));
	}

	/** 수정이 허용되는(발주확인 후) 상태 = 라인아이템이 all-NEW가 아닌 상태. */
	private OrderLineItem progressedItem() {
		return OrderLineItem.builder()
			.orderId(10L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
	}

	private Order orderWithMessage(String message) {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("O-1")
			.message(message)
			.build();
	}

	@Test
	@DisplayName("배송메시지를 수동으로 바꾸면 저장된다")
	void updatesMessage() {
		Order order = orderWithMessage("문 앞에 놔주세요");
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

		Order result = service().updateOrder(1L,
			OrderUpdateCommand.builder().message("경비실에 맡겨주세요").build());

		assertThat(result.getMessage()).isEqualTo("경비실에 맡겨주세요");
	}

	@Test
	@DisplayName("빈 문자열 배송메시지 → 클리어된다 (사용자가 의도적으로 지우는 경로)")
	void emptyMessage_clears() {
		Order order = orderWithMessage("문 앞에 놔주세요");
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

		Order result = service().updateOrder(1L,
			OrderUpdateCommand.builder().message("").build());

		assertThat(result.getMessage()).isEmpty();
	}

	@Test
	@DisplayName("배송메시지 null(미전송) → 기존 메시지 유지")
	void nullMessage_keepsExisting() {
		Order order = orderWithMessage("문 앞에 놔주세요");
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(1L)).thenReturn(List.of(progressedItem()));

		Order result = service().updateOrder(1L,
			OrderUpdateCommand.builder().address("서울시 강남구").build());

		assertThat(result.getMessage()).isEqualTo("문 앞에 놔주세요");
	}
}

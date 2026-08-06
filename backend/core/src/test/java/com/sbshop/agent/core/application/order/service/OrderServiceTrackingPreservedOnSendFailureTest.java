package com.sbshop.agent.core.application.order.service;

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

/**
 * D-125: 마켓 송장 전송 실패가 로컬 송장 기록까지 되돌리던 문제.
 *
 * 종전 계약(D-069 후속)은 "실패 시 롤백해 DB/마켓 정합 유지"였고, 그 근거는 "마켓 송장이
 * 동기화로 반영된다"였다. 그러나 ESM+(G마켓·옥션)는 Cafe24에 자체배송 자리표시자('00000000')만
 * 등록될 뿐 마켓 실송장이 유입되지 않는다는 것이 라이브로 확증됐다(주문 20260730-0000016).
 * 그 결과 동기화·이메일·수동 세 경로가 모두 막혀 송장을 기록할 방법이 사라졌다.
 *
 * 새 계약: 송장은 마켓 API 호출 성공 여부와 무관하게 실재하는 사실이므로 로컬 기록은 보존한다.
 * 전송 실패는 trackingSentToMarket을 올리지 않는 것으로 표현되어 재시도 대상으로 남는다.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTrackingPreservedOnSendFailureTest {

	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ShipmentRepository shipmentRepository;

	/**
	 * D-133: 송장 쓰기 통로는 <b>진짜 객체</b>를 끼운다. 목으로 대체하면 라인아이템 쓰기 자체가
	 * 사라져 기존 검증이 통과해도 아무것도 증명하지 못한다. {@code shipment_id}가 null인 이
	 * 테스트들에서는 통로가 배송을 건드리지 않으므로 종전과 동작이 같다 — 그 사실이 회귀 증거다.
	 */
	private LineItemShippingWriter shippingWriter() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}
	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private MarketplaceShippingService marketplaceShippingService;

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
		// 전송은 실패했으므로 마킹하지 않는다 — 재시도 대상으로 남는다.
		assertThat(item.getShippingData().getTrackingSentToMarket()).isNotEqualTo(Boolean.TRUE);
		verify(orderLineItemRepository).save(same(item));
	}

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

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> service().updateShippingInfo(4L, command()))
			.isInstanceOf(IllegalStateException.class);
	}
}

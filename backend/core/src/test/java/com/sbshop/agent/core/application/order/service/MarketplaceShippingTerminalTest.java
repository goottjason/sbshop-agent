package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * D-E6 회귀: 쿠팡 배송상태 잠금("배송진행상태가 유효하지 않습니다")은 재시도 불가(terminal)로 분류돼야
 * 무한 재시도 루프를 끊을 수 있다. 일시 오류는 재시도 가능(failed, non-terminal)으로 남는다.
 */
class MarketplaceShippingTerminalTest {

	private OrderLineItem shippedItem() {
		return OrderLineItem.builder()
			.orderId(1L)
			.productId(2500L)
			.shippingData(ShippingData.builder()
				.trackingNo("315398790560")
				.shippingStatus(ShippingStatus.SHIPPED)
				.shippingCarrier(ShippingCarrier.LOTTE_LOGISTICS)
				.build())
			.build();
	}

	private MarketplaceShippingService serviceWithPortThrowing(RuntimeException toThrow) {
		OrderRepository orderRepo = mock(OrderRepository.class);
		MarketCredentialRepository credRepo = mock(MarketCredentialRepository.class);
		MarketOrderPort coupangPort = mock(MarketOrderPort.class);

		when(coupangPort.getMarketType()).thenReturn(MarketType.COUPANG);
		Order order = Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("14101552820428")
			.shipmentBoxId("708248067784723")
			.build();
		when(orderRepo.findById(1L)).thenReturn(Optional.of(order));
		when(credRepo.findByMarketType(any())).thenReturn(Optional.empty());
		doThrow(toThrow).when(coupangPort).shipOrder(any(), any(), any(), any(), any());

		return new MarketplaceShippingService(orderRepo, credRepo, List.of(coupangPort));
	}

	@Test
	void 배송상태_잠금_오류는_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("쿠팡 송장업로드 실패: 배송진행상태가 유효하지 않습니다. [주문번호 : 14101552820428]"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.sent()).isFalse();
		assertThat(result.isFailed()).isTrue();
		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 일시_오류는_재시도가능_failed로_남는다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("Connection timed out"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isFailed()).isTrue();
		assertThat(result.isTerminal()).isFalse();
	}
}

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

	// ===== D-123: 쿠팡 외 마켓의 영구 거부도 terminal로 분류해야 재시도 루프가 끊긴다 =====

	@Test
	void 십일번가_존재하지_않는_배송번호는_terminal로_분류된다() {
		// 11번가가 해당 배송건을 갖고 있지 않다는 뜻 — 같은 페이로드를 재전송해도 영원히 성공하지 못한다.
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("11번가 발송처리 실패: 존재하지 않는 배송번호 입니다."));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 카페24_주문상태_변경불가는_terminal로_분류된다() {
		// Cafe24 422 — 이미 shipping 상태라 배송 등록을 거부한다. 재시도 불가.
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("Cafe24 API POST 호출 실패: 422 Unprocessable Entity: "
				+ "{\"error\":{\"code\":422,\"message\":\"You cannot change to that order state.\"}}"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 카페24_일시오류_5xx는_재시도가능으로_남는다() {
		// 영구/일시 구분이 뭉개지면 안 된다 — 서버 오류는 재시도 대상.
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("Cafe24 API POST 호출 실패: 503 Service Unavailable"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isFailed()).isTrue();
		assertThat(result.isTerminal()).isFalse();
	}
}

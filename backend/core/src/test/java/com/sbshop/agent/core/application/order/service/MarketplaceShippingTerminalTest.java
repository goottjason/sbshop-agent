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
			.build();
		when(orderRepo.findById(1L)).thenReturn(Optional.of(order));
		when(credRepo.findByMarketType(any())).thenReturn(Optional.empty());
		doThrow(toThrow).when(coupangPort).shipOrder(any(), any(), any(), any(), any());
		doThrow(toThrow).when(coupangPort).updateTracking(any(), any(), any(), any(), any());

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

	@Test
	void 십일번가_존재하지_않는_배송번호는_terminal이_아니라_재시도_대상이다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("11번가 발송처리 실패: 존재하지 않는 배송번호 입니다."));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isTerminal()).isFalse();
		assertThat(result.isFailed()).isTrue();
	}

	@Test
	void 카페24_주문상태_변경불가는_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("Cafe24 API POST 호출 실패: 422 Unprocessable Entity: "
				+ "{\"error\":{\"code\":422,\"message\":\"You cannot change to that order state.\"}}"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 스토어_주문상태_확인하세요는_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("스마트스토어 발송 실패(9999): 주문상태 및 클레임상태를 확인하세요"
				+ " — 상품주문 2026073137353041"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 사유가_원인_체인에만_있어도_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("스마트스토어 주문 발송(shipOrder) 실패",
				new RuntimeException("스마트스토어 발송 실패(9999): 주문상태 및 클레임상태를 확인하세요")));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 스토어_택배사코드_오류는_terminal이_아니라_재시도_대상이다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("스마트스토어 발송 실패(104119): 택배사코드 확인 — 상품주문 2026073137353041"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isFalse();
		assertThat(result.isFailed()).isTrue();
	}

	@Test
	void 십일번가_주문상태_이미_변경은_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("11번가 발송처리 실패: 해당 배송번호의 주문상태가 이미 변경 되었습니다."
				+ " 변경된 상태 : 배송중"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 십일번가_구매확정_상태잠금도_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("11번가 발송처리 실패: 해당 배송번호의 주문상태가 이미 변경 되었습니다."
				+ " 변경된 상태 : 구매확정"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 십일번가_상태잠금이_원인_체인에만_있어도_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("11번가 API 요청 실패: /rest/ordservices/reqdelivery/...",
				new RuntimeException("11번가 발송처리 실패: 해당 배송번호의 주문상태가 이미 변경 되었습니다."
					+ " 변경된 상태 : 배송중")));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 카페24_마켓플레이스_주문_수정불가는_terminal로_분류된다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("Cafe24 API PUT 호출 실패(422): {\"error\":{\"code\":422,\"message\":"
				+ "\"Shipping information (tracking number, shipping carrier code) cannot be edited "
				+ "for marketplace orders.\"}}"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), true);

		assertThat(result.isTerminal()).isTrue();
	}

	@Test
	void 카페24_일시오류_5xx는_재시도가능으로_남는다() {
		MarketplaceShippingService service = serviceWithPortThrowing(
			new RuntimeException("Cafe24 API POST 호출 실패: 503 Service Unavailable"));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(), false);

		assertThat(result.isFailed()).isTrue();
		assertThat(result.isTerminal()).isFalse();
	}
}

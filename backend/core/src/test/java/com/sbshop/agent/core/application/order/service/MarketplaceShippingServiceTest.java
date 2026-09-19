package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketplaceShippingServiceTest {
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private MarketCredentialRepository credentialRepository;

	@Test
	@DisplayName("마켓 port 예외 시: 예외를 던지지 않고 실패 결과를 반환한다")
	void portException_returnsFailureResult_notThrow() {
		Order order = order(MarketType.COUPANG);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.COUPANG);
		doThrow(new RuntimeException("쿠팡 송장 업로드 실패: 400"))
			.when(port).shipOrder(any(), any(), any(), anyString(), any());

		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		OrderLineItem item = shippedItem(1L, false);
		MarketShippingResult result = service.sendTrackingToMarketplace(item, false);

		assertThat(result.isFailed()).isTrue();
		assertThat(result.sent()).isFalse();
		assertThat(result.failureReason()).contains("400");
	}

	@Test
	@DisplayName("전송 성공 시: sent=true 결과를 반환한다")
	void portSuccess_returnsSentResult() {
		Order order = order(MarketType.COUPANG);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.COUPANG);

		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, false), false);

		assertThat(result.sent()).isTrue();
		assertThat(result.isFailed()).isFalse();
		verify(port).shipOrder(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("배송 어댑터 미지원 마켓: skipped 결과(실패 아님)")
	void noAdapter_returnsSkipped() {
		Order order = order(MarketType.CAFE24);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.CAFE24))
			.thenReturn(Optional.empty());

		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of());

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, false), false);

		assertThat(result.skipped()).isTrue();
		assertThat(result.isFailed()).isFalse();
	}

	@Test
	@DisplayName("이미 송장 존재(invoiceAlreadyExists=true): updateTracking 사용, 예외 시 실패 결과")
	void alreadySent_updateTrackingException_returnsFailure() {
		Order order = order(MarketType.COUPANG);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.COUPANG);
		doThrow(new RuntimeException("수정 실패"))
			.when(port).updateTracking(any(), any(), any(), anyString(), any());

		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, true), true);

		assertThat(result.isFailed()).isTrue();
		verify(port, never()).shipOrder(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("invoiceAlreadyExists=true ⇒ updateTracking 호출, shipOrder 미호출(마켓에 송장 이미 존재)")
	void invoiceExists_usesUpdateTracking_notShipOrder() {
		Order order = order(MarketType.COUPANG);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.COUPANG);
		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, false), true);

		assertThat(result.sent()).isTrue();
		verify(port).updateTracking(any(), any(), any(), anyString(), any());
		verify(port, never()).shipOrder(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("invoiceAlreadyExists=false ⇒ shipOrder 호출, updateTracking 미호출(진짜 최초 등록)")
	void invoiceAbsent_usesShipOrder_notUpdateTracking() {
		Order order = order(MarketType.COUPANG);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.COUPANG);
		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, true), false);

		assertThat(result.sent()).isTrue();
		verify(port).shipOrder(any(), any(), any(), anyString(), any());
		verify(port, never()).updateTracking(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("D-306: cafe24_order_id 없는 G마켓 주문은 마켓 호출 없이 terminal로 종결한다")
	void gmarketWithoutCafe24OrderId_returnsTerminal_withoutCallingPort() {
		Order order = order(MarketType.GMARKET);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.GMARKET))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.GMARKET);
		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, true), true);

		assertThat(result.isTerminal()).isTrue();
		assertThat(result.sent()).isFalse();
		assertThat(result.failureReason()).contains("Cafe24 원본 주문 없음");
		verify(port, never()).updateTracking(any(), any(), any(), anyString(), any());
		verify(port, never()).shipOrder(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("D-306: cafe24_order_id 없는 옥션 주문도 마켓 호출 없이 terminal로 종결한다")
	void auctionWithoutCafe24OrderId_returnsTerminal_withoutCallingPort() {
		Order order = order(MarketType.AUCTION);
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.AUCTION))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.AUCTION);
		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, false), false);

		assertThat(result.isTerminal()).isTrue();
		verify(port, never()).shipOrder(any(), any(), any(), anyString(), any());
	}

	@Test
	@DisplayName("D-306 회귀: cafe24_order_id 보유 G마켓 주문은 종전대로 마켓에 전송한다")
	void gmarketWithCafe24OrderId_sendsNormally() {
		Order order = order(MarketType.GMARKET);
		order.setMarketSpecificDataFromMap(Map.of("cafe24_order_id", "20260919-0000031"));
		when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
		when(credentialRepository.findByMarketType(MarketType.GMARKET))
			.thenReturn(Optional.of(mock(MarketCredential.class)));

		MarketOrderPort port = portFor(MarketType.GMARKET);
		MarketplaceShippingService service = new MarketplaceShippingService(
			orderRepository, credentialRepository, List.of(port));

		MarketShippingResult result = service.sendTrackingToMarketplace(shippedItem(1L, true), true);

		assertThat(result.sent()).isTrue();
		assertThat(result.isTerminal()).isFalse();
		verify(port).updateTracking(any(), any(), any(), anyString(), any());
	}

	private OrderLineItem shippedItem(Long orderId, boolean alreadySent) {
		return OrderLineItem.builder()
			.orderId(orderId)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.trackingNo("TRK-123")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingSentToMarket(alreadySent ? Boolean.TRUE : null)
				.build())
			.build();
	}

	private Order order(MarketType marketType) {
		return Order.builder()
			.marketType(marketType)
			.marketOrderNo("ORD-1")
			.build();
	}

	private MarketOrderPort portFor(MarketType type) {
		MarketOrderPort port = mock(MarketOrderPort.class);
		when(port.getMarketType()).thenReturn(type);
		return port;
	}
}

package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrackingMismatchResolverTest {

	@Mock
	MarketplaceShippingService marketplaceShippingService;
	@Mock
	OrderLineItemRepository orderLineItemRepository;
	@Mock
	LineItemShippingWriter shippingWriter;

	@InjectMocks
	TrackingMismatchResolver resolver;

	private Shipment shipment(String mail, String market) {
		Shipment s = Shipment.builder().orderId(1L).marketShipmentNo("SH-1").build();
		s.applyTracking(mail, null, Boolean.FALSE);
		s.applyMarketTracking(market);
		org.springframework.test.util.ReflectionTestUtils.setField(s, "id", 5L);
		return s;
	}

	private OrderLineItem item() {
		return OrderLineItem.builder().orderId(1L).quantity(1).build();
	}

	@Test
	@DisplayName("쿠팡 불일치는 마켓에 다시 보낸다 — 우리 송장이 진실이다")
	void coupangResends() {
		OrderLineItem li = item();
		when(orderLineItemRepository.findByShipmentId(5L)).thenReturn(List.of(li));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(new MarketShippingResult(true, false, false, null));

		resolver.resolve(MarketType.COUPANG, shipment("MAIL-1", "MARKET-1"));

		verify(marketplaceShippingService).sendTrackingToMarketplace(li, true);
	}

	@Test
	@DisplayName("쿠팡 재전송이 실패하면 사람이 볼 수 있게 남긴다")
	void coupangResendFailureMarksManualFix() {
		OrderLineItem li = item();
		when(orderLineItemRepository.findByShipmentId(5L)).thenReturn(List.of(li));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(new MarketShippingResult(false, false, false, "거부됨"));

		resolver.resolve(MarketType.COUPANG, shipment("MAIL-1", "MARKET-1"));

		verify(shippingWriter).markManualFixRequired(li);
	}

	@Test
	@DisplayName("나머지 마켓은 보내지 않고 표시만 한다 — 발송 후 수정 API 가 없다")
	void otherMarketsOnlyMark() {
		OrderLineItem li = item();
		when(orderLineItemRepository.findByShipmentId(5L)).thenReturn(List.of(li));

		resolver.resolve(MarketType.SMART_STORE, shipment("MAIL-1", "MARKET-1"));

		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
		verify(shippingWriter).markManualFixRequired(li);
	}

	@Test
	@DisplayName("일치하면 아무 것도 하지 않는다")
	void matchedDoesNothing() {
		resolver.resolve(MarketType.COUPANG, shipment("SAME", "SAME"));

		verify(orderLineItemRepository, never()).findByShipmentId(any());
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
	}

	@Test
	@DisplayName("재전송 중 예외가 나도 동기화를 멈추지 않는다")
	void exceptionDoesNotPropagate() {
		OrderLineItem li = item();
		when(orderLineItemRepository.findByShipmentId(5L)).thenReturn(List.of(li));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenThrow(new RuntimeException("네트워크 오류"));

		resolver.resolve(MarketType.COUPANG, shipment("MAIL-1", "MARKET-1"));

		assertThat(true).isTrue();
	}
}

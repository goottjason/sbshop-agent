package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.application.order.service.LineItemShippingWriter;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

class EmailFetcherMarketSyncTruthTest {

	private ShipmentRepository shipmentRepository;
	private OrderLineItemRepository lineItemRepository;
	private MarketplaceShippingService shippingService;
	private EmailFetcherService service;

	private static final String REAL = "315399497965";
	private static final String FAKE = "363092185283";

	@BeforeEach
	void setUp() {
		shipmentRepository = mock(ShipmentRepository.class);
		lineItemRepository = mock(OrderLineItemRepository.class);
		shippingService = mock(MarketplaceShippingService.class);
		when(lineItemRepository.save(any(OrderLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		when(lineItemRepository.findByShipmentId(any())).thenReturn(List.of());

		OrderRepository orderRepository = mock(OrderRepository.class);
		when(orderRepository.findById(any())).thenReturn(Optional.empty());
		service = new EmailFetcherService(null, null, lineItemRepository, orderRepository, shippingService,
			mock(ActionLogService.class), null);
		ReflectionTestUtils.setField(service, "shippingWriter",
			new LineItemShippingWriter(shipmentRepository, lineItemRepository));
	}

	@Test
	@DisplayName("플래그만 참이고 마켓은 다른 송장을 갖고 있으면 스킵하지 않고 전송을 시도한다")
	void attemptsSendWhenMarketActuallyHasDifferentTracking() {
		itemWithMarketOutOfSync(false);
		when(shippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(MarketShippingResult.ofTerminal("스마트스토어 발송 실패(9999): 주문상태 및 클레임상태를 확인하세요"));

		service.processIherbShipment(email());

		verify(shippingService).sendTrackingToMarketplace(any(), anyBoolean());
	}

	@Test
	@DisplayName("마켓이 영구 거부하면 배송에 '수동수정 필요'가 남는다 — 화면이 사람에게 넘길 근거")
	void marksManualFixOnTerminalRejection() {
		itemWithMarketOutOfSync(false);
		when(shippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(MarketShippingResult.ofTerminal("스마트스토어 발송 실패(9999): 주문상태 및 클레임상태를 확인하세요"));

		service.processIherbShipment(email());

		Shipment shipment = shipmentRepository.findById(12L).orElseThrow();
		assertThat(shipment.isManualFixRequired()).isTrue();
	}

	@Test
	@DisplayName("이미 수동수정 대기 중이면 매 사이클 재전송하지 않는다 — 사람이 고칠 때까지 조용히 기다린다")
	void doesNotRetryWhileWaitingForManualFix() {
		itemWithMarketOutOfSync(true);

		service.processIherbShipment(email());

		verify(shippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
	}

	@Test
	@DisplayName("마켓이 같은 송장을 갖고 있으면 종전대로 스킵한다(회귀)")
	void skipsWhenMarketReallyHasSameTracking() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(1L).quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("344163905").build())
			.shippingData(ShippingData.builder()
				.trackingNo(REAL).shippingStatus(ShippingStatus.SHIPPED).trackingSentToMarket(true).build())
			.build();
		ReflectionTestUtils.setField(item, "id", 467L);
		item.assignShipmentId(13L);
		Shipment shipment = Shipment.builder()
			.orderId(1L).marketShipmentNo("BOX").trackingNo(REAL).marketTrackingNo(REAL).build();
		ReflectionTestUtils.setField(shipment, "id", 13L);
		when(shipmentRepository.findById(13L)).thenReturn(Optional.of(shipment));
		when(lineItemRepository.findBySourcingData_SourcingOrderNo("344163905")).thenReturn(List.of(item));

		OrderEmailParser.IherbShipmentData data = mock(OrderEmailParser.IherbShipmentData.class);
		when(data.getOrderNo()).thenReturn("344163905");
		when(data.getTrackingNo()).thenReturn(REAL);

		service.processIherbShipment(data);

		verify(shippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
	}

	private OrderLineItem itemWithMarketOutOfSync(boolean manualFixRequired) {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(1L).quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("344143953").build())
			.shippingData(ShippingData.builder()
				.trackingNo(REAL).shippingCarrier(ShippingCarrier.LOTTE_LOGISTICS)
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingSentToMarket(true)
				.build())
			.build();
		ReflectionTestUtils.setField(item, "id", 460L);
		item.assignShipmentId(12L);

		Shipment shipment = Shipment.builder()
			.orderId(1L).marketShipmentNo("PKG").trackingNo(REAL)
			.marketTrackingNo(FAKE)
			.build();
		ReflectionTestUtils.setField(shipment, "id", 12L);
		if (manualFixRequired) {
			shipment.markManualFixRequired();
		}
		when(shipmentRepository.findById(12L)).thenReturn(Optional.of(shipment));
		when(lineItemRepository.findBySourcingData_SourcingOrderNo("344143953")).thenReturn(List.of(item));
		return item;
	}

	private OrderEmailParser.IherbShipmentData email() {
		OrderEmailParser.IherbShipmentData data = mock(OrderEmailParser.IherbShipmentData.class);
		when(data.getOrderNo()).thenReturn("344143953");
		when(data.getTrackingNo()).thenReturn(REAL);
		when(data.getCarrier()).thenReturn("롯데택배");
		return data;
	}
}

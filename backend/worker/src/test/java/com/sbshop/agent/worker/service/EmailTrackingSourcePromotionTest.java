package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.service.LineItemShippingWriter;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 마켓이 먼저 알려준 진짜 송장은 동기화가 채택해 {@code MARKET}으로 기록된다. 그 뒤 iHerb 메일이
 * 도착해도 <b>값이 같으면</b> 이메일 경로가 {@code sameTracking} 분기로 빠져 값을 쓰지 않는다 —
 * 승격을 놓치면 진짜 송장이 영영 ✍(진위 불명)로 남는다. 이 기능에서 가장 놓치기 쉬운 경로다.
 */
class EmailTrackingSourcePromotionTest {

	private static final String REAL = "315399497965";

	private ShipmentRepository shipmentRepository;
	private OrderLineItemRepository lineItemRepository;
	private MarketplaceShippingService shippingService;
	private EmailFetcherService service;

	@BeforeEach
	void setUp() {
		shipmentRepository = mock(ShipmentRepository.class);
		lineItemRepository = mock(OrderLineItemRepository.class);
		shippingService = mock(MarketplaceShippingService.class);
		when(lineItemRepository.save(any(OrderLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		when(lineItemRepository.findByShipmentId(any())).thenReturn(List.of());
		when(shippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(MarketShippingResult.ofSent());

		OrderRepository orderRepository = mock(OrderRepository.class);
		when(orderRepository.findById(any())).thenReturn(Optional.empty());
		service = new EmailFetcherService(null, null, lineItemRepository, orderRepository, shippingService,
			mock(com.sbshop.agent.core.application.actionlog.ActionLogService.class), null);
		ReflectionTestUtils.setField(service, "shippingWriter",
			new LineItemShippingWriter(shipmentRepository, lineItemRepository));
	}

	private OrderEmailParser.IherbShipmentData email() {
		OrderEmailParser.IherbShipmentData data = mock(OrderEmailParser.IherbShipmentData.class);
		when(data.getOrderNo()).thenReturn("344143953");
		when(data.getTrackingNo()).thenReturn(REAL);
		when(data.getCarrier()).thenReturn("롯데택배");
		return data;
	}

	@Test
	@DisplayName("값이 같아도 이메일이 확인하면 출처가 EMAIL로 승격된다")
	void promotesToEmailWhenValueUnchanged() {
		// 마켓이 먼저 알려준 진짜 송장 — 값은 이메일과 같고 출처만 MARKET이다.
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("S-1").build();
		shipment.applyTracking(REAL, ShippingCarrier.LOTTE_LOGISTICS, true);
		shipment.applyMarketTracking(REAL);
		shipment.applyTrackingSource(TrackingSource.MARKET);
		ReflectionTestUtils.setField(shipment, "id", 12L);
		when(shipmentRepository.findById(12L)).thenReturn(Optional.of(shipment));

		OrderLineItem item = OrderLineItem.builder()
			.orderId(1L).quantity(1).shipmentId(12L)
			.sourcingData(SourcingData.builder().sourcingOrderNo("344143953").build())
			.shippingData(ShippingData.builder()
				.trackingNo(REAL).shippingCarrier(ShippingCarrier.LOTTE_LOGISTICS)
				.shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
		when(lineItemRepository.findBySourcingData_SourcingOrderNo("344143953")).thenReturn(List.of(item));

		service.processIherbShipment(email());

		assertThat(shipment.getTrackingNo()).isEqualTo(REAL);          // 값은 그대로
		assertThat(shipment.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);   // 출처만 승격
	}
}

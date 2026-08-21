package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.config.EmailAccountProperties;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import com.sbshop.agent.core.application.order.service.LineItemShippingWriter;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailFetcherTrackingFillTest {

	@Mock
	EmailAccountProperties properties;
	@Mock
	OrderEmailParser parser;
	@Mock
	OrderLineItemRepository orderLineItemRepository;
	@Mock
	OrderRepository orderRepository;
	@Mock
	MarketplaceShippingService marketplaceShippingService;

	@InjectMocks
	EmailFetcherService service;

	@Mock
	ShipmentRepository shipmentRepository;

	@BeforeEach
	void injectRealShippingWriter() {
		ReflectionTestUtils.setField(service, "shippingWriter",
			new LineItemShippingWriter(shipmentRepository, orderLineItemRepository));
	}

	@Captor
	ArgumentCaptor<OrderLineItem> savedItemCaptor;

	@Test
	@DisplayName("D-121: DELIVERED 주문도 송장을 기록한다 — 마켓 전송은 생략")
	void deliveredOrder_recordsTrackingWithoutMarketSend() {
		OrderLineItem item = itemWithStatus(ShippingStatus.DELIVERED);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));

		service.processIherbShipment(shipment());

		verify(orderLineItemRepository).save(savedItemCaptor.capture());
		ShippingData saved = savedItemCaptor.getValue().getShippingData();
		assertThat(saved.getTrackingNo()).isEqualTo("424437727991");
		assertThat(saved.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(saved.getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
	}

	@Test
	@DisplayName("D-121: 발주확인 전(NEW) 주문도 송장을 기록한다 — 마켓 전송은 생략")
	void newOrder_recordsTrackingWithoutMarketSend() {
		OrderLineItem item = itemWithStatus(ShippingStatus.NEW);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));

		service.processIherbShipment(shipment());

		verify(orderLineItemRepository).save(savedItemCaptor.capture());
		assertThat(savedItemCaptor.getValue().getShippingData().getTrackingNo())
			.isEqualTo("424437727991");
		assertThat(savedItemCaptor.getValue().getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.NEW);
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
	}

	private OrderLineItem itemWithStatus(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHERB-1").build())
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	private OrderEmailParser.IherbShipmentData shipment() {
		return OrderEmailParser.IherbShipmentData.builder()
			.orderNo("IHERB-1")
			.trackingNo("424437727991")
			.carrier("CJGLS")
			.emailAccount("test@iherb")
			.build();
	}
}

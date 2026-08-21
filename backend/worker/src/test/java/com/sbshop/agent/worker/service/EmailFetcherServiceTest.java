package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.config.EmailAccountProperties;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

@ExtendWith(MockitoExtension.class)
class EmailFetcherServiceTest {

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
	void 이미SHIPPED이지만_이메일_송장이_다르면_진짜송장으로_교정하고_마켓수정경로로_전송한다() {
		OrderLineItem item = shippedItem("FAKE123", ShippingCarrier.CJ_LOGISTICS);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), eq(true)))
			.thenReturn(MarketShippingResult.ofSent());

		service.processIherbShipment(shipment("REAL999", "DHL"));

		verify(orderLineItemRepository, times(2)).save(savedItemCaptor.capture());
		assertThat(savedItemCaptor.getValue().getShippingData().getTrackingNo()).isEqualTo("REAL999");
		assertThat(savedItemCaptor.getValue().getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.SHIPPED);
		assertThat(savedItemCaptor.getValue().getShippingData().getShippingCarrier())
			.isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		verify(marketplaceShippingService).sendTrackingToMarketplace(any(), eq(true));
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), eq(false));
	}

	@Test
	void DISPATCHED이고_마켓미동기화면_이메일송장을_마켓수정경로로_전송한다() {
		OrderLineItem item = dispatchedItem("FAKE123", ShippingCarrier.CJ_LOGISTICS);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), eq(true)))
			.thenReturn(MarketShippingResult.ofSent());

		service.processIherbShipment(shipment("REAL999", "DHL"));

		verify(orderLineItemRepository, times(2)).save(savedItemCaptor.capture());
		assertThat(savedItemCaptor.getValue().getShippingData().getTrackingNo()).isEqualTo("REAL999");
		assertThat(savedItemCaptor.getValue().getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.DISPATCHED);
		verify(marketplaceShippingService).sendTrackingToMarketplace(any(), eq(true));
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), eq(false));
	}

	@Test
	void 이미SHIPPED이고_이메일_송장이_같으면_교정_경로를_타지않는다() {
		OrderLineItem item = shippedItem("SAME555", ShippingCarrier.CJ_LOGISTICS);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));

		service.processIherbShipment(shipment("SAME555", "DHL"));

		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), eq(true));
		verify(orderLineItemRepository, never()).save(any());
	}

	@Test
	void 이미_실행중이면_두번째_fetchAndProcessEmails_호출은_본처리를_스킵한다() throws Exception {
		when(properties.getAccounts())
			.thenReturn(List.of(new EmailAccountProperties.Account()));

		AtomicBoolean reentrantProcessed =
			new AtomicBoolean(false);
		when(orderLineItemRepository.findIherbItemsNeedingEmailProcessing())
			.thenAnswer(inv -> {
				if (reentrantProcessed.compareAndSet(false, true)) {
					service.fetchAndProcessEmails();
				}
				return List.of();
			});

		service.fetchAndProcessEmails();

		verify(orderLineItemRepository, times(1)).findIherbItemsNeedingEmailProcessing();
	}

	@Test
	void 실행_종료후_락이_해제되어_다음_호출은_정상_진행한다() {
		when(properties.getAccounts())
			.thenReturn(List.of(new EmailAccountProperties.Account()));
		when(orderLineItemRepository.findIherbItemsNeedingEmailProcessing())
			.thenReturn(List.of());

		service.fetchAndProcessEmails();
		service.fetchAndProcessEmails();

		verify(orderLineItemRepository, times(2)).findIherbItemsNeedingEmailProcessing();
	}

	private OrderLineItem shippedItem(String trackingNo, ShippingCarrier carrier) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHERB-1").build())
			.shippingData(ShippingData.builder()
				.trackingNo(trackingNo)
				.shippingCarrier(carrier)
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingSentToMarket(true)
				.build())
			.build();
	}

	private OrderLineItem dispatchedItem(String trackingNo, ShippingCarrier carrier) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHERB-1").build())
			.shippingData(ShippingData.builder()
				.trackingNo(trackingNo)
				.shippingCarrier(carrier)
				.shippingStatus(ShippingStatus.DISPATCHED)
				.build())
			.build();
	}

	private OrderEmailParser.IherbShipmentData shipment(String trackingNo, String carrier) {
		return OrderEmailParser.IherbShipmentData.builder()
			.orderNo("IHERB-1")
			.trackingNo(trackingNo)
			.carrier(carrier)
			.emailAccount("test@iherb")
			.build();
	}
}

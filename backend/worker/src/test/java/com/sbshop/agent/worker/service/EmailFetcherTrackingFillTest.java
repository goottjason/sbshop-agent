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

/**
 * D-121: 발송메일이 도착했을 때 라인아이템 상태가 PREPARING이 아니면 송장을 통째로 버리던 문제.
 * 옥션 실사례 — 배송완료(DELIVERED)로 넘어간 주문에 송장이 영원히 비어 있었다.
 * 송장은 기록하되, 마켓이 받아주지 않는 상태에서는 마켓 전송을 시도하지 않는다.
 */
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

	/**
	 * D-133: 송장 쓰기 통로는 <b>진짜 객체</b>를 끼운다. {@code @InjectMocks}가 목을 넣거나 null로
	 * 남기면 라인아이템 쓰기 자체가 사라져, 검증이 통과해도 아무것도 증명하지 못한다.
	 * 이 테스트들의 라인아이템은 {@code shipment_id}가 null이므로 통로는 배송을 건드리지 않는다 —
	 * 종전과 동작이 같다는 사실이 곧 회귀 증거다.
	 */
	@BeforeEach
	void injectRealShippingWriter() {
		ReflectionTestUtils.setField(service, "shippingWriter",
			new LineItemShippingWriter(shipmentRepository, orderLineItemRepository));
	}

	@Captor
	ArgumentCaptor<OrderLineItem> savedItemCaptor;

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
		// 배송상태는 마켓이 진실 원본 — 이메일이 건드리지 않는다.
		assertThat(saved.getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		// 배송완료 주문에 송장 등록을 시도하면 마켓이 거부한다 — 전송하지 않는다.
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
}

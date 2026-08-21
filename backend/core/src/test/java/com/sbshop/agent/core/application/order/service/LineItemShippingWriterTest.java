package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

@ExtendWith(MockitoExtension.class)
class LineItemShippingWriterTest {
	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;

	@Test
	@DisplayName("배송이 붙지 않은 라인아이템은 종전과 똑같이 라인아이템에만 쓴다 — 현재 240행 전부")
	void writesOnlyLineItemWhenNoShipment() {
		OrderLineItem item = itemWithShipment(null, ShippingData.builder().build());

		writer().applyShipping(item, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("424079080471");
		verify(orderLineItemRepository).save(item);
		verifyNoInteractions(shipmentRepository);
	}

	@Test
	@DisplayName("배송이 붙어 있으면 배송에도 송장·택배사를 쓴다")
	void writesThroughToShipment() {
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("2716448228").build();
		when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
		when(orderLineItemRepository.findByShipmentId(7L)).thenReturn(List.of());
		OrderLineItem item = itemWithShipment(7L, ShippingData.builder().build());

		writer().applyShipping(item, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		verify(shipmentRepository).save(shipment);
	}

	@Test
	@DisplayName("진행상태는 배송으로 올리지 않는다 — 같은 배송에서도 상품마다 갈린다")
	void doesNotPropagateStatusToShipment() {
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("2716448228").build();
		when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
		when(orderLineItemRepository.findByShipmentId(7L)).thenReturn(List.of());
		OrderLineItem item = itemWithShipment(7L, ShippingData.builder().build());

		writer().applyShipping(item, ShippingData.builder()
			.trackingNo("424079080471")
			.shippingStatus(ShippingStatus.DISPATCHED)
			.build());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DISPATCHED);
		assertThat(shipment.getDeliveryStatus()).isNull();
	}

	@Test
	@DisplayName("같은 배송의 형제 라인아이템에도 송장이 미러된다 — 송장 편집은 배송 단위")
	void mirrorsTrackingToSiblingsOfSameShipment() {
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("2716448228").build();
		OrderLineItem edited = itemWithShipment(7L, ShippingData.builder().build());
		OrderLineItem sibling = itemWithShipment(7L,
			ShippingData.builder().shippingStatus(ShippingStatus.NEW).build());
		when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
		when(orderLineItemRepository.findByShipmentId(7L)).thenReturn(List.of(edited, sibling));

		writer().applyShipping(edited, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		assertThat(sibling.getShippingData().getTrackingNo()).isEqualTo("424079080471");
		assertThat(sibling.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
		verify(orderLineItemRepository).save(sibling);
	}

	@Test
	@DisplayName("송장이 null이면 배송의 기존 송장을 지우지 않는다")
	void nullTrackingDoesNotClearShipment() {
		Shipment shipment = Shipment.builder()
			.orderId(1L).marketShipmentNo("2716448228").trackingNo("424079080471").build();
		when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
		when(orderLineItemRepository.findByShipmentId(7L)).thenReturn(List.of());
		OrderLineItem item = itemWithShipment(7L, ShippingData.builder().build());

		writer().applyShipping(item,
			ShippingData.builder().shippingStatus(ShippingStatus.PREPARING).build());

		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("배송을 못 찾아도 라인아이템 기록은 남는다 — 미러 실패가 사실을 지우지 않는다")
	void lineItemWriteSurvivesMissingShipment() {
		when(shipmentRepository.findById(7L)).thenReturn(Optional.empty());
		OrderLineItem item = itemWithShipment(7L, ShippingData.builder().build());

		writer().applyShipping(item, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("424079080471");
		verify(orderLineItemRepository).save(item);
		verify(shipmentRepository, never()).save(any(Shipment.class));
	}

	@Test
	@DisplayName("마켓 전송 완료 마킹도 배송에 반영된다")
	void marksTrackingAsSentOnShipmentToo() {
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("2716448228").build();
		when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
		when(orderLineItemRepository.findByShipmentId(7L)).thenReturn(List.of());
		OrderLineItem item = itemWithShipment(7L, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		writer().markTrackingAsSent(item);

		assertThat(item.getShippingData().getTrackingSentToMarket()).isTrue();
		assertThat(shipment.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("배송 없는 라인아이템의 전송 완료 마킹은 종전과 같다")
	void marksTrackingAsSentWithoutShipment() {
		OrderLineItem item = itemWithShipment(null, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		writer().markTrackingAsSent(item);

		assertThat(item.getShippingData().getTrackingSentToMarket()).isTrue();
		verify(orderLineItemRepository).save(item);
		verifyNoInteractions(shipmentRepository);
	}

	private LineItemShippingWriter writer() {
		return new LineItemShippingWriter(shipmentRepository, orderLineItemRepository);
	}

	private static OrderLineItem itemWithShipment(Long shipmentId, ShippingData data) {
		return OrderLineItem.builder()
			.orderId(1L).quantity(1).shipmentId(shipmentId).shippingData(data)
			.build();
	}

	private static ShippingData tracking(String no, ShippingCarrier carrier) {
		return ShippingData.builder().trackingNo(no).shippingCarrier(carrier).build();
	}
}

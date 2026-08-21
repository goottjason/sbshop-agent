package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrackingSourceWritePathTest {
	private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
	private final OrderLineItemRepository lineItemRepository = mock(OrderLineItemRepository.class);

	@Test
	@DisplayName("출처를 지정하면 배송에 기록된다")
	void writerRecordsGivenSource() {
		Shipment shipment = shipment();
		when(shipmentRepository.findById(9L)).thenReturn(Optional.of(shipment));
		when(lineItemRepository.findByShipmentId(9L)).thenReturn(List.of());
		LineItemShippingWriter writer = new LineItemShippingWriter(shipmentRepository, lineItemRepository);

		writer.applyShipping(item(9L),
			ShippingData.builder().trackingNo("424438293101")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS).build(),
			TrackingSource.EMAIL);

		assertThat(shipment.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("출처를 지정하지 않은 기존 호출은 출처를 건드리지 않는다 — 호출부를 한꺼번에 고치지 않아도 안전하다")
	void writerLeavesSourceUntouchedWithoutArgument() {
		Shipment shipment = shipment();
		shipment.applyTrackingSource(TrackingSource.EMAIL);
		when(shipmentRepository.findById(9L)).thenReturn(Optional.of(shipment));
		when(lineItemRepository.findByShipmentId(9L)).thenReturn(List.of());
		LineItemShippingWriter writer = new LineItemShippingWriter(shipmentRepository, lineItemRepository);

		writer.applyShipping(item(9L),
			ShippingData.builder().trackingNo("111122223333").build());

		assertThat(shipment.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("마켓 값을 채택할 때만 MARKET으로 기록한다 — 우리 송장이 있으면 채택하지 않으므로 출처도 그대로다")
	void upsertRecordsMarketOnlyWhenAdopted() {
		Shipment adopted = shipment();
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(1L, "S-1"))
			.thenReturn(Optional.of(adopted));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		OrderShipmentUpsertService service = new OrderShipmentUpsertService(shipmentRepository, lineItemRepository);

		service.upsertShipment(1L, MarketShipmentDto.builder()
			.marketShipmentNo("S-1").trackingNo("6079990333504").build());

		assertThat(adopted.getTrackingSource()).isEqualTo(TrackingSource.MARKET);
	}

	@Test
	@DisplayName("우리 송장이 이미 있으면 마켓 값은 채택되지 않고 출처도 바뀌지 않는다")
	void upsertKeepsSourceWhenWeAlreadyKnowTracking() {
		Shipment ours = shipment();
		ours.applyTracking("424438293101", ShippingCarrier.CJ_LOGISTICS, null);
		ours.applyTrackingSource(TrackingSource.EMAIL);
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(1L, "S-1"))
			.thenReturn(Optional.of(ours));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		OrderShipmentUpsertService service = new OrderShipmentUpsertService(shipmentRepository, lineItemRepository);

		service.upsertShipment(1L, MarketShipmentDto.builder()
			.marketShipmentNo("S-1").trackingNo("6079990333504").build());

		assertThat(ours.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	private Shipment shipment() {
		return Shipment.builder().orderId(1L).marketShipmentNo("S-1").build();
	}

	private OrderLineItem item(Long shipmentId) {
		return OrderLineItem.builder().orderId(1L).quantity(1).shipmentId(shipmentId)
			.shippingData(ShippingData.builder().build()).build();
	}
}

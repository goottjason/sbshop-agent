package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.lang.reflect.Field;
import org.assertj.core.api.Assertions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderShipmentUpsertServiceTest {
	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;

	@Test
	@DisplayName("배송이 없으면 새로 만든다")
	void createsWhenAbsent() {
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "2716448228"))
			.thenReturn(Optional.empty());
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("2716448228")
			.trackingNo("424079080471")
			.carrier(ShippingCarrier.CJ_LOGISTICS)
			.build());

		assertThat(result.getOrderId()).isEqualTo(100L);
		assertThat(result.getMarketShipmentNo()).isEqualTo("2716448228");
		assertThat(result.getTrackingNo()).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("같은 배송식별자면 기존 배송을 갱신한다 — 중복 생성하지 않는다")
	void updatesExistingInsteadOfDuplicating() {
		Shipment existing = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("2716448228")
			.build();
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "2716448228"))
			.thenReturn(Optional.of(existing));
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("2716448228")
			.trackingNo("6079990333504")
			.carrier(ShippingCarrier.KOREA_POST)
			.build());

		assertThat(result).isSameAs(existing);
		assertThat(result.getTrackingNo()).isEqualTo("6079990333504");
		assertThat(result.getShippingCarrier()).isEqualTo(ShippingCarrier.KOREA_POST);
	}

	@Test
	@DisplayName("자리표시자·빈 송장은 기존 실송장을 덮지 않는다")
	void placeholderTrackingDoesNotOverwrite() {
		Shipment existing = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.trackingSentToMarket(true)
			.build();
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "D1"))
			.thenReturn(Optional.of(existing));
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.trackingNo("00000000")
			.carrier(ShippingCarrier.HANJIN)
			.build());

		assertThat(result.getTrackingNo()).isEqualTo("424079080471");
		assertThat(result.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(result.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("마켓이 준 실송장이면 마켓 보유(trackingSentToMarket=true)로 마킹한다")
	void marksMarketOwnershipOnRealTracking() {
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "D1"))
			.thenReturn(Optional.empty());
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.build());

		assertThat(result.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("배송식별자가 없으면 저장하지 않고 예외를 던진다")
	void rejectsMissingShipmentNo() {
		OrderShipmentUpsertService service = service();
		MarketShipmentDto dto = MarketShipmentDto.builder().trackingNo("X").build();

		Assertions
			.assertThatThrownBy(() -> service.upsertShipment(100L, dto))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("배송 식별자");

		verify(shipmentRepository, never()).save(any());
	}

	@Test
	@DisplayName("라인아이템을 배송에 연결하면 송장 미러가 함께 내려간다")
	void linkMirrorsTrackingToLineItem() {
		Shipment shipment = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.trackingSentToMarket(true)
			.build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder().orderId(100L).quantity(1).build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository).save(captor.capture());
		OrderLineItem saved = captor.getValue();
		assertThat(saved.getShipmentId()).isEqualTo(7L);
		ShippingData shipping = saved.getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipping.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("미러가 라인아이템의 진행상태를 건드리지 않는다")
	void linkKeepsLineItemStatus() {
		Shipment shipment = Shipment.builder()
			.orderId(100L).marketShipmentNo("D1").trackingNo("424079080471").build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(ShippingStatus.NEW)
				.build())
			.build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		assertThat(item.getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("배송이 아직 송장을 못 받았으면(null) 라인아이템의 기존 실송장을 지우지 않는다")
	void linkPreservesExistingTrackingWhenShipmentHasNone() {
		Shipment shipment = Shipment.builder()
			.orderId(100L).marketShipmentNo("D1").build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.trackingNo("424079080471")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.trackingSentToMarket(true)
				.build())
			.build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		ShippingData shipping = item.getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipping.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
		assertThat(item.getShipmentId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("배송의 각 필드는 독립적으로 반영된다 — 일부만 실값이어도 나머지 기존값은 유지된다")
	void linkAppliesEachFieldIndependently() {
		Shipment shipment = Shipment.builder()
			.orderId(100L).marketShipmentNo("D1")
			.trackingNo("999888777")
			.build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.trackingNo("424079080471")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
				.trackingSentToMarket(true)
				.build())
			.build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		ShippingData shipping = item.getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("999888777");
		assertThat(shipping.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("라인아이템의 shippingData가 null이어도(레거시 행) NPE 없이 연결된다")
	void linkToleratesNullShippingData() {
		Shipment shipment = Shipment.builder()
			.orderId(100L).marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.trackingSentToMarket(true)
			.build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder().orderId(100L).quantity(1).build();
		setShippingDataNull(item);
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		ShippingData shipping = item.getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipping.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	private OrderShipmentUpsertService service() {
		return new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository);
	}

	private static void setId(Object entity, Long id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void setShippingDataNull(OrderLineItem item) {
		try {
			Field field = OrderLineItem.class.getDeclaredField("shippingData");
			field.setAccessible(true);
			field.set(item, null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}

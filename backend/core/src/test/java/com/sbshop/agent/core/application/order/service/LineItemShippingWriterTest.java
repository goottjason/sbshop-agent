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

/**
 * D-133: 송장 쓰기를 <b>배송 단일 원본</b>으로 모은다.
 *
 * <p>설계 §4.4는 "라인아이템 컬럼에 직접 쓰는 코드는 남기지 않는다"고 정했지만, 실제로는
 * 이메일 파이프라인·수동 입력·발송 처리기가 각자 라인아이템에 직접 쓰고 있었다. 배송 계층이
 * 켜지는 순간 두 계층이 갈리고, 발송처리가 <b>배송 단위</b>로 바뀌므로(설계 §6.1) 배송이 모르는
 * 송장은 마켓에 나가지 않는다 — iHerb 이메일 → 송장 자동반영 파이프라인이 조용히 멈춘다.
 *
 * <p>이 통로가 그 불변식을 지킨다. {@code shipment_id}가 NULL인 현재 240행에서는
 * 종전과 완전히 같은 동작이고(배송을 건드리지 않는다), 2단계가 배송을 채우기 시작하면
 * 자동으로 올바른 동작이 된다.
 */
@ExtendWith(MockitoExtension.class)
class LineItemShippingWriterTest {

	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;

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

	@Test
	@DisplayName("배송이 붙지 않은 라인아이템은 종전과 똑같이 라인아이템에만 쓴다 — 현재 240행 전부")
	void writesOnlyLineItemWhenNoShipment() {
		OrderLineItem item = itemWithShipment(null, ShippingData.builder().build());

		writer().applyShipping(item, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		assertThat(item.getShippingData().getTrackingNo()).isEqualTo("424079080471");
		verify(orderLineItemRepository).save(item);
		// 배송 계층을 조회조차 하지 않아야 한다 — 1단계의 "동작 불변"이 여기서 유지된다.
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
		// 정나영 건: 같은 주문·같은 배송지인데 순번1 결제완료 / 순번2 발송완료. 상태를 배송에 두면
		// 이 사실을 표현할 수 없다(설계 §3.2). 배송에는 배송 자체의 상태만 둔다.
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
		// 한 배송 = 한 송장이다(설계 §3.1). 11번가는 묶음배송번호가 같으면 한 번의 발송처리로
		// 나머지 주문번호까지 모두 발송 처리된다(-3308 문서) — 형제가 같은 송장을 갖는 것이 실제다.
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("2716448228").build();
		OrderLineItem edited = itemWithShipment(7L, ShippingData.builder().build());
		OrderLineItem sibling = itemWithShipment(7L,
			ShippingData.builder().shippingStatus(ShippingStatus.NEW).build());
		when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
		when(orderLineItemRepository.findByShipmentId(7L)).thenReturn(List.of(edited, sibling));

		writer().applyShipping(edited, tracking("424079080471", ShippingCarrier.CJ_LOGISTICS));

		assertThat(sibling.getShippingData().getTrackingNo()).isEqualTo("424079080471");
		// 형제의 진행상태는 건드리지 않는다.
		assertThat(sibling.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.NEW);
		verify(orderLineItemRepository).save(sibling);
	}

	@Test
	@DisplayName("송장이 null이면 배송의 기존 송장을 지우지 않는다")
	void nullTrackingDoesNotClearShipment() {
		// D-125/D-119: 값이 없다는 것과 "송장이 없다"는 다르다. 상태만 바꾸는 호출이
		// 배송의 실송장을 소거하면 안 된다.
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
		// D-125의 교훈: 송장은 부수 경로의 성공 여부와 무관하게 실재하는 사실이다.
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
		// D-129의 "마켓이 이 송장을 갖고 있는가"는 §7에서 배송의 플래그를 보도록 옮겨진다.
		// 라인아이템만 마킹하면 그 배지가 영구히 미반영으로 남는다.
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
}

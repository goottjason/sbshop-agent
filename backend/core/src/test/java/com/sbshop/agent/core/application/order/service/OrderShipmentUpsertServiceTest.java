package com.sbshop.agent.core.application.order.service;

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

/**
 * 배송 upsert는 <b>마켓 배송식별자로만</b> 매칭한다. 배열 순서에 기대지 않는다 —
 * Cafe24 현행 방식(인덱스 짝짓기)은 마켓이 순서를 바꾸면 엉뚱한 상품에 송장을 붙인다.
 *
 * <p>{@code linkToShipment}는 설계 4.4의 미러 규칙을 구현한다. 라인아이템의 송장 컬럼은
 * 기존 그리드·엑셀·정산 쿼리·이메일 파이프라인이 전부 읽으므로 당분간 유지하되,
 * <b>쓰기는 배송이 단일 원본</b>이고 라인아이템에는 복제만 내려쓴다.
 */
@ExtendWith(MockitoExtension.class)
class OrderShipmentUpsertServiceTest {

	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;

	private OrderShipmentUpsertService service() {
		return new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository);
	}

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
		// D-119/D-120: 마켓이 미발송 주문에 '00000000'이나 빈 문자열을 담아 주는 경우가 있고,
		// 그 값으로 실제 송장을 덮으면 배송정보가 유실된다.
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
		// D-129: 마켓이 실송장을 알려줬다는 것은 곧 마켓이 그 송장을 보유한다는 뜻이다.
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

		org.assertj.core.api.Assertions
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
		// 상태는 상품주문마다 갈리므로 배송이 덮으면 안 된다
		// (11번가 20260731088778989: 순번 1 결제완료 / 순번 2 발송완료).
		Shipment shipment = Shipment.builder()
			.orderId(100L).marketShipmentNo("D1").trackingNo("424079080471").build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(com.sbshop.agent.core.domain.order.enums.ShippingStatus.NEW)
				.build())
			.build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		assertThat(item.getShippingData().getShippingStatus())
			.isEqualTo(com.sbshop.agent.core.domain.order.enums.ShippingStatus.NEW);
	}

	@Test
	@DisplayName("배송이 아직 송장을 못 받았으면(null) 라인아이템의 기존 실송장을 지우지 않는다")
	void linkPreservesExistingTrackingWhenShipmentHasNone() {
		// 이메일 파이프라인이 라인아이템에 먼저 채워둔 실송장이 있는 상태에서, 마켓이
		// 아직 송장을 안 준 배송으로 linkToShipment가 불려도 유실되면 안 된다(D-125 시나리오).
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
		// 배송에 택배사·마켓전송여부가 없으니(null) 기존 값이 유지된다.
		assertThat(shipping.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("라인아이템의 shippingData가 null이어도(레거시 행) NPE 없이 연결된다")
	void linkToleratesNullShippingData() {
		// ShippingData는 @Embeddable이라 전 컬럼이 NULL인 기존 행에서 Hibernate가
		// null을 넣을 수 있다 — 빌더의 null 가드는 리플렉션 로딩을 거치지 않는다.
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

	/** BaseEntity.id는 생성자로 못 넣으므로 리플렉션으로 채운다(테스트 전용). */
	private static void setId(Object entity, Long id) {
		try {
			java.lang.reflect.Field field =
				com.sbshop.agent.core.domain.common.BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 레거시 행(전 컬럼 NULL)에서 Hibernate가 만드는 상태를 리플렉션으로 재현한다(테스트 전용). */
	private static void setShippingDataNull(OrderLineItem item) {
		try {
			java.lang.reflect.Field field = OrderLineItem.class.getDeclaredField("shippingData");
			field.setAccessible(true);
			field.set(item, null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}

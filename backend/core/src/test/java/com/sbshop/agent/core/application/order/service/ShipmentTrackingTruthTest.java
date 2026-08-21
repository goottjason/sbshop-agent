package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;

/**
 * <b>송장의 진실은 발송처(iHerb 발송메일)다. 마켓 값은 "마켓이 알고 있는 값"일 뿐이다.</b>
 *
 * <p>2026-08-07 실측: 이메일이 진짜 송장으로 교정(11:34) → 스토어 동기화가 마켓의 가송장으로
 * 원복(11:38) → 11번가도 원복(11:50). 되돌아가면 우리 값 = 마켓 값이 되어 배지도 꺼지고,
 * <b>고쳐야 할 일이 있다는 사실이 화면에서 사라진다.</b> 화면·엑셀·고객 응대는 가송장을 진짜처럼 안내한다.
 *
 * <p>네이버는 발송된 주문의 송장 수정 API를 제공하지 않으므로(공식 답변 2건 + 라이브 시험) 시스템의
 * 역할은 대신 고치는 것이 아니라 <b>고쳐야 할 건을 정확히 짚어 주는 것</b>이다.
 */
class ShipmentTrackingTruthTest {

	private ShipmentRepository shipmentRepository;
	private OrderShipmentUpsertService service;

	@BeforeEach
	void setUp() {
		shipmentRepository = mock(ShipmentRepository.class);
		OrderLineItemRepository lineItemRepository = mock(OrderLineItemRepository.class);
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		when(lineItemRepository.findByShipmentId(anyLong())).thenReturn(List.of());
		service = new OrderShipmentUpsertService(shipmentRepository, lineItemRepository);
	}

	private Shipment existing(String trackingNo) {
		Shipment s = Shipment.builder()
			.orderId(1L).marketShipmentNo("PKG-1").trackingNo(trackingNo)
			.shippingCarrier(ShippingCarrier.LOTTE_LOGISTICS).build();
		ReflectionTestUtils.setField(s, "id", 10L);
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(anyLong(), anyString()))
			.thenReturn(Optional.of(s));
		return s;
	}

	private MarketShipmentDto fromMarket(String trackingNo) {
		return MarketShipmentDto.builder()
			.marketShipmentNo("PKG-1").trackingNo(trackingNo)
			.carrier(ShippingCarrier.CJ_LOGISTICS).build();
	}

	@Test
	@DisplayName("마켓의 가송장이 우리가 아는 실제 송장을 덮지 않는다 — 오늘의 원복 사건 회귀")
	void marketValueDoesNotOverwriteOurTracking() {
		Shipment shipment = existing("315399497965"); // 이메일이 준 진짜 송장

		service.upsertShipment(1L, fromMarket("363092185283")); // 마켓의 가송장

		assertThat(shipment.getTrackingNo()).isEqualTo("315399497965");
		assertThat(shipment.getMarketTrackingNo()).isEqualTo("363092185283");
		// 택배사도 우리 값을 지킨다 — 송장과 택배사가 어긋나면 조회 자체가 안 된다.
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.LOTTE_LOGISTICS);
	}

	@Test
	@DisplayName("우리가 송장을 모르면 마켓 값을 그대로 채택한다 — 마켓이 유일한 출처인 경우(기존 동작)")
	void adoptsMarketValueWhenWeHaveNone() {
		Shipment shipment = existing(null);

		service.upsertShipment(1L, fromMarket("424437728013"));

		assertThat(shipment.getTrackingNo()).isEqualTo("424437728013");
		assertThat(shipment.getMarketTrackingNo()).isEqualTo("424437728013");
	}

	@Test
	@DisplayName("사람이 마켓에서 고쳐 두 값이 같아지면 수동수정 표시가 스스로 꺼진다")
	void manualFixFlagClearsWhenMarketCatchesUp() {
		Shipment shipment = existing("315399497965");
		shipment.markManualFixRequired();

		service.upsertShipment(1L, fromMarket("315399497965")); // 마켓이 따라옴

		assertThat(shipment.isManualFixRequired()).isFalse();
	}

	@Test
	@DisplayName("아직 안 고쳤으면 수동수정 표시가 유지된다")
	void manualFixFlagStaysWhileMarketDiffers() {
		Shipment shipment = existing("315399497965");
		shipment.markManualFixRequired();

		service.upsertShipment(1L, fromMarket("363092185283"));

		assertThat(shipment.isManualFixRequired()).isTrue();
	}

	@Test
	@DisplayName("마켓이 빈 값·자리표시자를 주면 마켓 보유값도 기록하지 않는다(D-119/120 규율 유지)")
	void placeholderFromMarketIsIgnored() {
		Shipment shipment = existing("315399497965");

		service.upsertShipment(1L, fromMarket("00000000"));

		assertThat(shipment.getTrackingNo()).isEqualTo("315399497965");
		assertThat(shipment.getMarketTrackingNo()).isNull();
	}
}

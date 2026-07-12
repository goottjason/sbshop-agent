package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * G마켓/옥션 송장 역전송: 택배사 코드 매칭(/carriers) + order_item_code(주문상세) + shipments 바디 구성 검증.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24ShipmentServiceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock private Cafe24OrderApiPort port;
	private Cafe24ShipmentService service;

	@BeforeEach
	void setUp() throws Exception {
		service = new Cafe24ShipmentService(port);
		lenient().when(port.fetchCarriers()).thenReturn(MAPPER.readTree(
			"[{\"shipping_company_code\":\"0019\",\"shipping_carrier\":\"CJ대한통운\"},"
				+ "{\"shipping_company_code\":\"0006\",\"shipping_carrier\":\"롯데택배\"}]"));
		lenient().when(port.fetchOrderDetail("20260708-0000011")).thenReturn(MAPPER.readTree(
			"{\"items\":[{\"order_item_code\":\"20260708-0000011-01\",\"status_code\":\"N30\"}]}"));
	}

	/** 재키잉된 실주문: marketOrderNo=마켓 원본번호, cafe24_order_id=Cafe24 자체번호(마켓specific). */
	private Order gmarketOrder() {
		Order order = Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo("4466411168")
			.orderDate(LocalDateTime.now())
			.build();
		order.setMarketSpecificDataFromMap(Map.of("cafe24_order_id", "20260708-0000011"));
		return order;
	}

	@Test
	@DisplayName("송장등록/주문상세를 cafe24_order_id(20260708-0000011)로 타깃한다 — 마켓 원본번호(4466411168) 아님(C-1)")
	void shipTargetsCafe24OrderIdNotNativeNumber() {
		service.ship(gmarketOrder(), "1234567890", ShippingCarrier.CJ_LOGISTICS);

		// 주문상세 조회도 Cafe24 order_id로 타깃해야 order_item_code를 얻는다(원본번호면 404).
		verify(port).fetchOrderDetail("20260708-0000011");
		verify(port).registerShipment(eq("20260708-0000011"), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("cafe24_order_id가 없는 레거시 행은 marketOrderNo로 폴백해 타깃한다")
	void shipFallsBackToMarketOrderNoWhenNoCafe24OrderId() throws Exception {
		lenient().when(port.fetchOrderDetail("4466411168")).thenReturn(MAPPER.readTree("{\"items\":[]}"));
		Order legacy = Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo("4466411168")
			.orderDate(LocalDateTime.now())
			.build();

		service.ship(legacy, "1234567890", ShippingCarrier.CJ_LOGISTICS);

		verify(port).fetchOrderDetail("4466411168");
		verify(port).registerShipment(eq("4466411168"), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("CJ대한통운 송장을 몰 택배사 코드(0019)로 매칭하고 shipments 바디를 구성해 등록한다")
	void shipRegistersWithResolvedCarrierAndItemCodes() {
		service.ship(gmarketOrder(), "1234567890", ShippingCarrier.CJ_LOGISTICS);

		ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
		verify(port).registerShipment(eq("20260708-0000011"), bodyCaptor.capture());

		@SuppressWarnings("unchecked")
		Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
		@SuppressWarnings("unchecked")
		Map<String, Object> req = (Map<String, Object>) body.get("request");
		assertThat(req.get("tracking_no")).isEqualTo("1234567890");
		assertThat(req.get("shipping_company_code")).isEqualTo("0019");
		assertThat(req.get("status")).isEqualTo("shipping");
		@SuppressWarnings("unchecked")
		java.util.List<Object> codes = (java.util.List<Object>) req.get("order_item_code");
		assertThat(codes).containsExactly("20260708-0000011-01");
	}

	@Test
	@DisplayName("몰에 없는 택배사는 코드 매칭 실패로 예외를 던진다(실패 표면화)")
	void unmatchedCarrierThrows() {
		when(port.fetchCarriers()).thenReturn(MAPPER.createArrayNode()); // 택배사 없음
		assertThatThrownBy(() -> service.ship(gmarketOrder(), "123", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalStateException.class);
	}
}

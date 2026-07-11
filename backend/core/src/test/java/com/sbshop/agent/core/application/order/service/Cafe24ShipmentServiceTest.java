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

	private Order gmarketOrder() {
		return Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo("20260708-0000011")
			.orderDate(LocalDateTime.now())
			.build();
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

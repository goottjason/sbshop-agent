package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Cafe24ShipmentTrackingLookupTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("D-124: 자리표시자와 실값이 섞여 있으면 실값을 고른다")
	void picksMeaningfulTrackingAmongPlaceholders() {
		Cafe24OrderApiPort port = portReturning("00000000", "6079990333504");

		Cafe24ShipmentTrackingLookup.Found found = lookup(port).findRealTracking("20260730-0000016");

		assertThat(found).isNotNull();
		assertThat(found.trackingNo()).isEqualTo("6079990333504");
	}

	@Test
	@DisplayName("D-124: 자리표시자뿐이면 null — 가짜 송장을 실값으로 승격하지 않는다")
	void allPlaceholders_returnsNull() {
		Cafe24OrderApiPort port = portReturning("00000000");

		assertThat(lookup(port).findRealTracking("20260730-0000016")).isNull();
	}

	@Test
	@DisplayName("D-124: 배송건이 없어도 예외 없이 null")
	void noShipments_returnsNull() {
		Cafe24OrderApiPort port = mock(Cafe24OrderApiPort.class);
		when(port.fetchShipments(anyString())).thenReturn(MAPPER.createArrayNode());

		assertThat(lookup(port).findRealTracking("20260730-0000016")).isNull();
	}

	@Test
	@DisplayName("D-124: 조회 자체가 실패해도 동기화를 깨뜨리지 않고 null")
	void apiFailure_doesNotPropagate() {
		Cafe24OrderApiPort port = mock(Cafe24OrderApiPort.class);
		when(port.fetchShipments(anyString())).thenThrow(new RuntimeException("401 Unauthorized"));

		assertThat(lookup(port).findRealTracking("20260730-0000016")).isNull();
	}

	@Test
	@DisplayName("D-124: 주문 id가 없으면 API를 호출하지 않는다")
	void blankOrderId_skipsApiCall() {
		Cafe24OrderApiPort port = mock(Cafe24OrderApiPort.class);

		assertThat(lookup(port).findRealTracking("  ")).isNull();

		verify(port, never()).fetchShipments(anyString());
	}

	@Test
	@DisplayName("D-124: 택배사 코드도 함께 해석해 돌려준다")
	void resolvesCarrierCode() {
		Cafe24OrderApiPort port = mock(Cafe24OrderApiPort.class);
		ArrayNode shipments = MAPPER.createArrayNode();
		ObjectNode s = shipments.addObject();
		s.put("tracking_no", "6079990333504");
		s.put("shipping_company_name", "우체국택배");
		when(port.fetchShipments(anyString())).thenReturn(shipments);

		Cafe24ShipmentTrackingLookup.Found found = lookup(port).findRealTracking("20260730-0000016");

		assertThat(found.carrier()).isEqualTo(ShippingCarrier.KOREA_POST);
	}

	private Cafe24OrderApiPort portReturning(String... trackingNos) {
		Cafe24OrderApiPort port = mock(Cafe24OrderApiPort.class);
		ArrayNode shipments = MAPPER.createArrayNode();
		for (String no : trackingNos) {
			ObjectNode s = shipments.addObject();
			s.put("tracking_no", no);
			s.put("shipping_company_code", "0006");
			s.put("status", "shipping");
		}
		when(port.fetchShipments(anyString())).thenReturn(shipments);
		return port;
	}

	private Cafe24ShipmentTrackingLookup lookup(Cafe24OrderApiPort port) {
		return new Cafe24ShipmentTrackingLookup(port);
	}
}

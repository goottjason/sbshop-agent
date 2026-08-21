package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
 * D-151: 이미 배송중인 Cafe24 주문에 <b>새 배송건을 만들 수 없다</b>.
 *
 * <p>2026-08-08 라이브: G마켓 2건이 한 달간 매번
 * {@code 422 You cannot change to that order state}로 거부됐다. 두 주문 모두 Cafe24에서 이미
 * 배송중(N1)이고 배송건이 하나 등록돼 있었다(shipping_code=D-...-00, tracking_no=00000000 더미).
 * 그런데 우리는 수정이든 신규든 항상 POST /shipments(신규 등록)만 호출하고 있었다.
 *
 * <p>11번가·네이버와 달리 <b>Cafe24에는 수정 경로가 있다</b> —
 * {@code PUT /admin/orders/{order_no}/shipments/{shipping_code}} (라우트 존재 라이브 확인).
 * 따라서 배송건이 이미 있으면 새로 만들지 말고 그 배송건을 수정해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class Cafe24ShipmentUpdateTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private Cafe24OrderApiPort port;
	private Cafe24ShipmentService service;

	@BeforeEach
	void setUp() throws Exception {
		service = new Cafe24ShipmentService(port);
		lenient().when(port.fetchCarriers()).thenReturn(MAPPER.readTree(
			"[{\"shipping_company_code\":\"0019\",\"shipping_carrier\":\"CJ대한통운\"},"
				+ "{\"shipping_company_code\":\"0006\",\"shipping_carrier\":\"롯데택배\"}]"));
		lenient().when(port.fetchOrderDetail("20260630-0000017")).thenReturn(MAPPER.readTree(
			"{\"items\":[{\"order_item_code\":\"20260630-0000017-01\",\"status_code\":\"N1\"}]}"));
	}

	private Order gmarketOrder() {
		Order order = Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo("4462952064")
			.orderDate(LocalDateTime.now())
			.build();
		order.setMarketSpecificDataFromMap(Map.of("cafe24_order_id", "20260630-0000017"));
		return order;
	}

	@Test
	@DisplayName("배송건이 이미 있으면 신규 등록(POST) 대신 그 배송건을 수정(PUT)한다")
	void updatesExistingShipmentInsteadOfCreating() throws Exception {
		// 마켓이 "실제 송장"을 들고 있는 배송건 — 이때만 수정(PUT)이다.
		when(port.fetchShipments("20260630-0000017")).thenReturn(MAPPER.readTree(
			"[{\"shipping_code\":\"D-20260630-0000017-00\",\"tracking_no\":\"6079990378097\","
				+ "\"shipping_company_code\":\"0006\"}]"));

		service.ship(gmarketOrder(), "424438293101", ShippingCarrier.CJ_LOGISTICS);

		ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
		verify(port).updateShipment(eq("20260630-0000017"), eq("D-20260630-0000017-00"), body.capture());
		verify(port, never()).registerShipment(any(), any());

		Map<String, Object> req = request(body.getValue());
		assertThat(req.get("tracking_no")).isEqualTo("424438293101");
		assertThat(req.get("shipping_company_code")).isEqualTo("0019"); // CJ대한통운
		// 상태는 건드리지 않는다 — 이미 배송중인 주문에 status를 다시 보내면 같은 422를 부른다.
		assertThat(req).doesNotContainKey("status");
	}

	@Test
	@DisplayName("배송건이 있어도 실송장이 없으면 신규 등록(POST)한다 — Cafe24는 발송 전에도 배송건을 미리 만든다")
	void registersWhenExistingShipmentHasNoRealTracking() throws Exception {
		// 2026-08-08 라이브 확인: 아직 우리가 송장을 보내지 않은 주문(20260807-0000011)에도
		// 배송건 D-...-00이 tracking 없이 미리 존재한다. "배송건이 있으면 PUT"으로만 판단하면
		// 최초 전송까지 수정 경로로 새고, 마켓플레이스 주문은 PUT이 거부되므로(D-154)
		// 송장이 영영 마켓에 못 들어간다. 실송장을 가진 배송건일 때만 수정이다.
		when(port.fetchShipments("20260630-0000017")).thenReturn(MAPPER.readTree(
			"[{\"shipping_code\":\"D-20260630-0000017-00\",\"tracking_no\":null}]"));

		service.ship(gmarketOrder(), "424438293101", ShippingCarrier.CJ_LOGISTICS);

		verify(port).registerShipment(eq("20260630-0000017"), any());
		verify(port, never()).updateShipment(any(), any(), any());
	}

	@Test
	@DisplayName("배송건의 송장이 자리표시자(00000000)면 수정이 아니라 신규 등록으로 간다")
	void registersWhenExistingTrackingIsPlaceholder() throws Exception {
		// D-124: ESM+ 자체배송은 Cafe24에 00000000 더미만 남긴다 — 마켓이 실송장을 가진 게 아니다.
		when(port.fetchShipments("20260630-0000017")).thenReturn(MAPPER.readTree(
			"[{\"shipping_code\":\"D-20260630-0000017-00\",\"tracking_no\":\"00000000\"}]"));

		service.ship(gmarketOrder(), "424438293101", ShippingCarrier.CJ_LOGISTICS);

		verify(port).registerShipment(eq("20260630-0000017"), any());
		verify(port, never()).updateShipment(any(), any(), any());
	}

	@Test
	@DisplayName("배송건이 없으면 종전대로 신규 등록(POST)한다")
	void registersWhenNoShipmentExists() throws Exception {
		when(port.fetchShipments("20260630-0000017")).thenReturn(MAPPER.readTree("[]"));

		service.ship(gmarketOrder(), "424438293101", ShippingCarrier.CJ_LOGISTICS);

		verify(port).registerShipment(eq("20260630-0000017"), any());
		verify(port, never()).updateShipment(any(), any(), any());
	}

	@Test
	@DisplayName("실송장을 가진 배송건이 여럿이면 추측하지 않고 실패한다 — 엉뚱한 배송건을 고치면 되돌리기 어렵다")
	void failsLoudlyWhenMultipleShipmentsCarryRealTracking() throws Exception {
		// 모호한 것은 "수정 대상"이 여럿일 때뿐이다. 실송장이 없는 배송건이 여러 개인 경우는
		// 최초 전송이므로 종전 등록 경로로 가야 한다(다건 주문의 첫 전송을 깨뜨리지 않는다).
		when(port.fetchShipments("20260630-0000017")).thenReturn(MAPPER.readTree(
			"[{\"shipping_code\":\"D-20260630-0000017-00\",\"tracking_no\":\"6079990378097\"},"
				+ "{\"shipping_code\":\"D-20260630-0000017-01\",\"tracking_no\":\"6063465794604\"}]"));

		assertThatThrownBy(() ->
			service.ship(gmarketOrder(), "424438293101", ShippingCarrier.CJ_LOGISTICS))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("배송건이 여러");

		verify(port, never()).updateShipment(any(), any(), any());
		verify(port, never()).registerShipment(any(), any());
	}

	@Test
	@DisplayName("실송장 없는 배송건이 여럿이면 등록으로 간다 — 최초 전송을 막지 않는다")
	void registersWhenMultipleShipmentsHaveNoRealTracking() throws Exception {
		when(port.fetchShipments("20260630-0000017")).thenReturn(MAPPER.readTree(
			"[{\"shipping_code\":\"D-20260630-0000017-00\",\"tracking_no\":\"00000000\"},"
				+ "{\"shipping_code\":\"D-20260630-0000017-01\"}]"));

		service.ship(gmarketOrder(), "424438293101", ShippingCarrier.CJ_LOGISTICS);

		verify(port).registerShipment(eq("20260630-0000017"), any());
		verify(port, never()).updateShipment(any(), any(), any());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> request(Object body) {
		return (Map<String, Object>)((Map<String, Object>)body).get("request");
	}
}

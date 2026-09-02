package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class Cafe24LineItemMapperTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("단일 상품 주문은 배송 1 : 상품주문 1이다 — 현재 12행 전부의 형태")
	void singleItemOrder() {
		JsonNode o = order("{\"items\":[" + item("20260805-0000011-01", "D-20260805-0000011-00",
			"N20", null, null, "18185", "43300.00", 1) + "]}");

		List<MarketShipmentDto> shipments = Cafe24LineItemMapper.toShipments(o, "4476239169");

		assertThat(shipments).hasSize(1);
		MarketShipmentDto sh = shipments.get(0);
		assertThat(sh.getMarketShipmentNo()).isEqualTo("D-20260805-0000011-00");
		assertThat(sh.getLineItems()).hasSize(1);

		MarketLineItemDto li = sh.getLineItems().get(0);
		assertThat(li.getMarketLineItemNo()).isEqualTo("20260805-0000011-01");
		assertThat(li.getStatus()).isEqualTo(ShippingStatus.PREPARING);
		assertThat(li.getQuantity()).isEqualTo(1);
		assertThat(li.getOrderPrice()).isEqualByComparingTo("43300.00");
		assertThat(li.getTotalAmount()).isEqualByComparingTo("43300.00");
	}

	@Test
	@DisplayName("shipping_code로 배송을 나눈다 — 인덱스가 아니라 식별자로 짝짓는다")
	void groupsByShippingCode() {
		JsonNode o = order("{\"items\":["
			+ item("ORD-01", "D-A", "N30", "111111111111", "0006", "1", "10000", 1) + ","
			+ item("ORD-02", "D-B", "N20", null, null, "2", "7000", 1) + ","
			+ item("ORD-03", "D-A", "N30", "111111111111", "0006", "3", "5000", 2)
			+ "]}");

		List<MarketShipmentDto> shipments = Cafe24LineItemMapper.toShipments(o, "ORD");

		assertThat(shipments).hasSize(2);
		assertThat(shipmentOf(shipments, "D-A").getLineItems()).hasSize(2);
		assertThat(shipmentOf(shipments, "D-B").getLineItems()).hasSize(1);
		assertThat(shipmentOf(shipments, "D-A").getTrackingNo()).isEqualTo("111111111111");
		assertThat(shipmentOf(shipments, "D-B").getTrackingNo()).isNull();
	}

	@Test
	@DisplayName("마켓이 items 순서를 바꿔도 상품주문 식별자로 정확히 짝지어진다")
	void orderOfItemsDoesNotMatter() {
		String a = item("ORD-01", "D-A", "N30", "111111111111", "0006", "1", "10000", 1);
		String b = item("ORD-02", "D-B", "N20", null, null, "2", "7000", 1);

		List<MarketShipmentDto> forward = Cafe24LineItemMapper.toShipments(
			order("{\"items\":[" + a + "," + b + "]}"), "ORD");
		List<MarketShipmentDto> reversed = Cafe24LineItemMapper.toShipments(
			order("{\"items\":[" + b + "," + a + "]}"), "ORD");

		for (List<MarketShipmentDto> shipments : List.of(forward, reversed)) {
			MarketLineItemDto first = allItems(shipments).stream()
				.filter(li -> "ORD-01".equals(li.getMarketLineItemNo())).findFirst().orElseThrow();
			assertThat(first.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
			assertThat(shipmentOf(shipments, "D-A").getTrackingNo()).isEqualTo("111111111111");
			assertThat(shipmentOf(shipments, "D-B").getTrackingNo()).isNull();
		}
	}

	@Test
	@DisplayName("배송 식별자가 없으면 주문번호로 대체한다 — 배송 없는 주문은 만들지 않는다")
	void fallsBackToOrderNoAsShipmentNo() {
		JsonNode o = order("{\"items\":["
			+ item("ORD-01", null, "N20", null, null, "1", "10000", 1) + "]}");

		List<MarketShipmentDto> shipments = Cafe24LineItemMapper.toShipments(o, "4476239169");

		assertThat(shipments).hasSize(1);
		assertThat(shipments.get(0).getMarketShipmentNo()).isEqualTo("4476239169");
	}

	@Test
	@DisplayName("자리표시자 송장은 채택하지 않는다 (D-119)")
	void ignoresPlaceholderTracking() {
		JsonNode o = order("{\"items\":["
			+ item("ORD-01", "D-A", "N30", "00000000", "0006", "1", "10000", 1) + "]}");

		List<MarketShipmentDto> shipments = Cafe24LineItemMapper.toShipments(o, "ORD");

		assertThat(shipments.get(0).getTrackingNo()).isNull();
	}

	@Test
	@DisplayName("택배사 코드가 매핑되지 않으면 위조하지 않는다")
	void doesNotFabricateCarrier() {
		JsonNode o = order("{\"items\":["
			+ item("ORD-01", "D-A", "N30", "111111111111", null, "1", "10000", 1) + "]}");

		assertThat(shipmentOf(Cafe24LineItemMapper.toShipments(o, "ORD"), "D-A").getCarrier()).isNull();
	}

	@Test
	@DisplayName("상품 식별자는 marketSpecificData에 담는다 — product_no와 product_code 둘 다 필요하다")
	void carriesProductIdentifiers() {
		JsonNode o = order("{\"items\":["
			+ item("ORD-01", "D-A", "N20", null, null, "18185", "10000", 1) + "]}");

		MarketLineItemDto li = allItems(Cafe24LineItemMapper.toShipments(o, "ORD")).get(0);

		assertThat(li.getMarketSpecificData())
			.containsEntry("product_no", "18185")
			.containsEntry("product_code", "P000BAXL");
		assertThat(li.getProductName()).isEqualTo("라이프 익스텐션 CoQ10");
	}

	@Test
	@DisplayName("items가 비어 있어도 배송 1건으로 남긴다 — 주문을 드롭하지 않는다")
	void keepsOrderWithoutItems() {
		List<MarketShipmentDto> shipments = Cafe24LineItemMapper.toShipments(
			order("{\"items\":[]}"), "4476239169");

		assertThat(shipments).hasSize(1);
		assertThat(shipments.get(0).getLineItems()).hasSize(1);
		assertThat(shipments.get(0).getLineItems().get(0).getMarketLineItemNo()).isNull();
	}

	@Test
	@DisplayName("D-270: 클레임 코드는 배송 단계를 말해주지 않는다 — 접두어 뭉개기를 걷어냈다")
	void claimCodesDoNotCarryDeliveryStage() {
		assertThat(Cafe24LineItemMapper.mapStatus("C10")).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(Cafe24LineItemMapper.mapStatus("R20")).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(Cafe24LineItemMapper.mapStatus("E30")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("진행 코드를 매핑한다 — N10은 발주확인 전이라 신규다 (D-088)")
	void mapsProgressCodes() {
		assertThat(Cafe24LineItemMapper.mapStatus("N00")).isEqualTo(ShippingStatus.NEW);
		assertThat(Cafe24LineItemMapper.mapStatus("N10")).isEqualTo(ShippingStatus.NEW);
		assertThat(Cafe24LineItemMapper.mapStatus("N20")).isEqualTo(ShippingStatus.PREPARING);
		assertThat(Cafe24LineItemMapper.mapStatus("N30")).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(Cafe24LineItemMapper.mapStatus("N40")).isEqualTo(ShippingStatus.DELIVERED);
		// D-270: 구매확정은 배송완료와 다르다 — 확정되면 반품·교환 청구권이 닫힌다.
		assertThat(Cafe24LineItemMapper.mapStatus("N50")).isEqualTo(ShippingStatus.CONFIRMED);
	}

	@Test
	@DisplayName("모르는 코드는 UNKNOWN이다 — NEW로 폴백하지 않는다")
	void unknownCodeStaysUnknown() {
		assertThat(Cafe24LineItemMapper.mapStatus("N99")).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(Cafe24LineItemMapper.mapStatus("X1")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("빈 코드도 UNKNOWN이다 — 골격이 UNKNOWN을 덮지 않으므로 기존 상태가 보존된다")
	void blankCodeStaysUnknown() {
		assertThat(Cafe24LineItemMapper.mapStatus(null)).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(Cafe24LineItemMapper.mapStatus("  ")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("미연동 마켓상품은 마켓 쪽 판매자코드까지 담는다 — 이 값 말고는 범인을 지목할 단서가 없다")
	void carriesMarketSellerCodeForUnlinkedListing() {
		JsonNode o = order("{\"items\":[{"
			+ "\"order_item_code\":\"20260811-0000015-01\",\"shipping_code\":\"D-A\""
			+ ",\"order_status\":\"N20\",\"product_no\":-99999"
			+ ",\"product_code\":\"2005125893\",\"custom_product_code\":null"
			+ ",\"market_custom_variant_code\":\"5ffd2a8e27776\""
			+ ",\"product_name\":\"Pure Indian Foods 오리지널 기버터\""
			+ ",\"payment_amount\":\"64400\",\"quantity\":2}]}");

		MarketLineItemDto li = allItems(Cafe24LineItemMapper.toShipments(o, "ORD")).get(0);

		assertThat(li.getMarketSpecificData())
			.containsEntry("market_custom_variant_code", "5ffd2a8e27776")
			.containsEntry("product_code", "2005125893")
			.doesNotContainKey("custom_product_code");
	}

	private static JsonNode order(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String item(String itemCode, String shippingCode, String status,
		String trackingNo, String carrierCode, String productNo, String amount, int qty) {
		return "{\"order_item_code\":\"" + itemCode + "\""
			+ (shippingCode == null ? "" : ",\"shipping_code\":\"" + shippingCode + "\"")
			+ ",\"order_status\":\"" + status + "\""
			+ (trackingNo == null ? "" : ",\"tracking_no\":\"" + trackingNo + "\"")
			+ (carrierCode == null ? "" : ",\"shipping_company_code\":\"" + carrierCode + "\"")
			+ ",\"product_no\":\"" + productNo + "\",\"product_code\":\"P000BAXL\""
			+ ",\"custom_product_code\":\"220622IHB002\""
			+ ",\"product_name\":\"라이프 익스텐션 CoQ10\""
			+ ",\"payment_amount\":\"" + amount + "\",\"quantity\":" + qty + "}";
	}

	private static List<MarketLineItemDto> allItems(List<MarketShipmentDto> shipments) {
		List<MarketLineItemDto> all = new ArrayList<>();
		shipments.forEach(s -> all.addAll(s.getLineItems()));
		return all;
	}

	private static MarketShipmentDto shipmentOf(List<MarketShipmentDto> shipments, String no) {
		return shipments.stream().filter(s -> no.equals(s.getMarketShipmentNo()))
			.findFirst().orElseThrow(() -> new AssertionError("배송 " + no + " 없음"));
	}
}

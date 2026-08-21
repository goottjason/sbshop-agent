package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangThreeTierFetchTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private CoupangOrderApiPort api;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;

	private final CoupangStatusMapper statusMapper = new CoupangStatusMapper();

	@Test
	@DisplayName("단일 상품 주문은 배송 1 : 상품주문 1이다 — 8개월 407행 전부의 형태")
	void singleItemOrderStaysOneToOne() {
		stub("DELIVERING", arrayOf(row("2101945764711", "714841543016459", "315399491013", "롯데택배",
			"87763005527", "14502180649", "P000BFUS000A", "스테비아 잎 분말", "1", "24600")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		MarketOrderDto dto = result.get(0);
		assertThat(dto.getMarketOrderNo()).isEqualTo("2101945764711");
		assertThat(dto.getShipments()).hasSize(1);
		assertThat(allItems(dto)).hasSize(1);

		MarketShipmentDto shipment = dto.getShipments().get(0);
		assertThat(shipment.getMarketShipmentNo()).isEqualTo("714841543016459");
		assertThat(shipment.getTrackingNo()).isEqualTo("315399491013");
		assertThat(shipment.getCarrier()).isEqualTo(ShippingCarrier.LOTTE_LOGISTICS);

		MarketLineItemDto item = allItems(dto).get(0);
		assertThat(item.getQuantity()).isEqualTo(1);
		assertThat(item.getOrderPrice()).isEqualByComparingTo("24600");
		assertThat(item.getTotalAmount()).isEqualByComparingTo("24600");
		assertThat(item.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("한 배송에 상품이 여러 개면 라인아이템도 여러 개다 — 종전엔 orderItems[0]만 썼다")
	void parsesEveryOrderItem() {
		stub("ACCEPT", arrayOf(row("3000012345", "77001122", null, null,
			"111", "9001", "SKU-A", "칼슘", "1", "10000",
			"222", "9002", "SKU-B", "마그네슘", "2", "7000")));

		MarketOrderDto dto = fetch().get(0);

		assertThat(dto.getShipments()).hasSize(1);
		assertThat(allItems(dto)).hasSize(2);
		MarketLineItemDto second = allItems(dto).stream()
			.filter(i -> "마그네슘".equals(i.getProductName())).findFirst().orElseThrow();
		assertThat(second.getQuantity()).isEqualTo(2);
		assertThat(second.getOrderPrice()).isEqualByComparingTo("7000");
		assertThat(second.getTotalAmount()).isEqualByComparingTo("14000");
	}

	@Test
	@DisplayName("같은 주문번호에 행이 여러 개면 배송 여러 개로 묶인다 — 분할배송")
	void groupsMultipleRowsOfSameOrderIntoOneDto() {
		stub("DELIVERING", arrayOf(
			row("3000012345", "77001122", "111111111111", "CJ대한통운",
				"111", "9001", "SKU-A", "칼슘", "1", "10000"),
			row("3000012345", "77001133", "222222222222", "한진택배",
				"222", "9002", "SKU-B", "마그네슘", "1", "7000")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		MarketOrderDto dto = result.get(0);
		assertThat(dto.getShipments()).hasSize(2);
		assertThat(allItems(dto)).hasSize(2);
		assertThat(shipmentOf(dto, "77001122").getTrackingNo()).isEqualTo("111111111111");
		assertThat(shipmentOf(dto, "77001133").getTrackingNo()).isEqualTo("222222222222");
		assertThat(shipmentOf(dto, "77001133").getCarrier()).isEqualTo(ShippingCarrier.HANJIN);
	}

	@Test
	@DisplayName("상품주문 식별자는 배송박스와 상품을 함께 담는다 — 같은 상품이 두 박스에 나뉘어도 충돌하지 않는다")
	void lineItemKeyIncludesShipmentBox() {
		stub("DELIVERING", arrayOf(
			row("3000012345", "77001122", "111111111111", "CJ대한통운",
				"111", "9001", "SKU-A", "칼슘", "1", "10000"),
			row("3000012345", "77001133", "222222222222", "CJ대한통운",
				"111", "9001", "SKU-A", "칼슘", "1", "10000")));

		MarketOrderDto dto = fetch().get(0);

		List<String> keys = allItems(dto).stream().map(MarketLineItemDto::getMarketLineItemNo).toList();
		assertThat(keys).doesNotHaveDuplicates().hasSize(2);
		assertThat(keys).allSatisfy(k -> assertThat(k).contains("111"));
	}

	@Test
	@DisplayName("진행상태는 행 레벨이므로 그 배송의 라인아이템이 공유한다")
	void statusIsPerShipmentBox() {
		stub("DEPARTURE", arrayOf(row("3000012345", "77001122", "111111111111", "CJ대한통운",
			"111", "9001", "SKU-A", "칼슘", "1", "10000",
			"222", "9002", "SKU-B", "마그네슘", "1", "7000")));

		MarketOrderDto dto = fetch().get(0);

		assertThat(allItems(dto)).extracting(MarketLineItemDto::getStatus)
			.containsExactly(ShippingStatus.DISPATCHED, ShippingStatus.DISPATCHED);
	}

	@Test
	@DisplayName("취소된 상품만 CANCELED가 된다 — 부분취소는 상품 단위로 표현된다")
	void canceledItemBecomesCanceled() {
		String json = "{\"orderId\":3000012345,\"shipmentBoxId\":77001122,"
			+ "\"orderedAt\":\"2026-07-30T02:10:28\","
			+ "\"receiver\":{\"name\":\"홍길동\",\"postCode\":\"07997\",\"addr1\":\"서울\",\"addr2\":\"1\"},"
			+ "\"orderer\":{\"name\":\"김주문\"},"
			+ "\"orderItems\":["
			+ "{\"vendorItemId\":111,\"sellerProductId\":9001,\"externalVendorSkuCode\":\"SKU-A\","
			+ "\"vendorItemName\":\"칼슘\",\"shippingCount\":1,\"orderPrice\":10000,\"canceled\":false},"
			+ "{\"vendorItemId\":222,\"sellerProductId\":9002,\"externalVendorSkuCode\":\"SKU-B\","
			+ "\"vendorItemName\":\"마그네슘\",\"shippingCount\":1,\"orderPrice\":7000,\"canceled\":true}"
			+ "]}";
		stub("DELIVERING", arrayOf(json));

		MarketOrderDto dto = fetch().get(0);

		MarketLineItemDto alive = allItems(dto).stream()
			.filter(i -> "칼슘".equals(i.getProductName())).findFirst().orElseThrow();
		MarketLineItemDto dead = allItems(dto).stream()
			.filter(i -> "마그네슘".equals(i.getProductName())).findFirst().orElseThrow();

		assertThat(alive.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(dead.getStatus()).isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("주문 공통 필드는 주문 계층에 남고 라인아이템 레벨 평면 필드는 채우지 않는다")
	void keepsOrderLevelFieldsOnly() {
		stub("DELIVERING", arrayOf(row("3000012345", "77001122", "111111111111", "CJ대한통운",
			"111", "9001", "SKU-A", "칼슘", "1", "10000")));

		MarketOrderDto dto = fetch().get(0);

		assertThat(dto.getRecipientName()).isEqualTo("홍길동");
		assertThat(dto.getZipcode()).isEqualTo("07997");
		assertThat(dto.getCustomsClearanceNo()).isEqualTo("P200032008307");
		assertThat(dto.getOrdererName()).isEqualTo("김주문");
		assertThat(dto.getStatus()).isNull();
		assertThat(dto.getTrackingNo()).isNull();
		assertThat(dto.getMarketProductCode()).isNull();
		assertThat(dto.getShipments().get(0).getMarketShipmentNo()).isEqualTo("77001122");
	}

	@Test
	@DisplayName("배송박스 식별자가 없으면 주문번호로 대체한다 — 배송 없는 주문은 만들지 않는다")
	void fallsBackToOrderNoAsShipmentNo() {
		String json = "{\"orderId\":3000012345,\"orderedAt\":\"2026-07-30T02:10:28\","
			+ "\"receiver\":{\"name\":\"홍길동\",\"postCode\":\"07997\",\"addr1\":\"서울\",\"addr2\":\"1\"},"
			+ "\"orderer\":{\"name\":\"김주문\"},"
			+ "\"orderItems\":[{\"vendorItemId\":111,\"sellerProductId\":9001,"
			+ "\"externalVendorSkuCode\":\"SKU-A\",\"vendorItemName\":\"칼슘\","
			+ "\"shippingCount\":1,\"orderPrice\":10000,\"canceled\":false}]}";
		stub("ACCEPT", arrayOf(json));

		MarketOrderDto dto = fetch().get(0);

		assertThat(dto.getShipments()).hasSize(1);
		assertThat(dto.getShipments().get(0).getMarketShipmentNo()).isEqualTo("3000012345");
	}

	@Test
	@DisplayName("상품이 하나도 없는 행도 배송 1건으로 남는다 — 주문을 드롭하지 않는다")
	void keepsOrderWithoutItems() {
		String json = "{\"orderId\":3000012345,\"shipmentBoxId\":77001122,"
			+ "\"orderedAt\":\"2026-07-30T02:10:28\","
			+ "\"receiver\":{\"name\":\"홍길동\",\"postCode\":\"07997\",\"addr1\":\"서울\",\"addr2\":\"1\"},"
			+ "\"orderer\":{\"name\":\"김주문\"},\"orderItems\":[]}";
		stub("ACCEPT", arrayOf(json));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getShipments()).hasSize(1);
		assertThat(allItems(result.get(0))).hasSize(1);
		assertThat(allItems(result.get(0)).get(0).getMarketLineItemNo()).isNull();
	}

	@Test
	@DisplayName("서로 다른 주문은 섞이지 않는다")
	void distinctOrdersStaySeparate() {
		stub("DELIVERING", arrayOf(
			row("3000012345", "77001122", "111111111111", "CJ대한통운",
				"111", "9001", "SKU-A", "칼슘", "1", "10000"),
			row("3000099999", "77009999", "999999999999", "CJ대한통운",
				"222", "9002", "SKU-B", "마그네슘", "1", "7000")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(2);
		assertThat(result).extracting(MarketOrderDto::getMarketOrderNo)
			.containsExactlyInAnyOrder("3000012345", "3000099999");
	}

	private CoupangOrderAdapter adapter() {
		return new CoupangOrderAdapter(api, statusMapper, orderRepository,
			orderLineItemRepository, marketRegistrationRepository, shipmentRepository);
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("A00123456");
		when(c.getAccessKey()).thenReturn("ak");
		when(c.getSecretKey()).thenReturn("sk");
		return c;
	}

	private static String row(String orderId, String boxId, String invoiceNo, String carrier,
		String... items) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"orderId\":").append(orderId)
			.append(",\"shipmentBoxId\":").append(boxId)
			.append(",\"orderedAt\":\"2026-07-30T02:10:28\"")
			.append(",\"paidAt\":\"2026-07-30T02:10:30\"")
			.append(",\"parcelPrintMessage\":\"문앞\"")
			.append(",\"splitShipping\":false,\"ableSplitShipping\":false")
			.append(",\"receiver\":{\"name\":\"홍길동\",\"safeNumber\":\"0503-1234\","
				+ "\"postCode\":\"07997\",\"addr1\":\"서울 양천구\",\"addr2\":\"101동\"}")
			.append(",\"orderer\":{\"name\":\"김주문\"}")
			.append(",\"overseaShippingInfoDto\":{\"ordererPhoneNumber\":\"010-1111-2222\","
				+ "\"personalCustomsClearanceCode\":\"P200032008307\"}");
		if (invoiceNo != null) {
			sb.append(",\"invoiceNumber\":\"").append(invoiceNo).append('"');
		}
		if (carrier != null) {
			sb.append(",\"deliveryCompanyName\":\"").append(carrier).append('"');
		}
		sb.append(",\"orderItems\":[");
		for (int i = 0; i < items.length; i += 6) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append("{\"vendorItemId\":").append(items[i])
				.append(",\"sellerProductId\":").append(items[i + 1])
				.append(",\"externalVendorSkuCode\":\"").append(items[i + 2]).append('"')
				.append(",\"vendorItemName\":\"").append(items[i + 3]).append('"')
				.append(",\"shippingCount\":").append(items[i + 4])
				.append(",\"orderPrice\":").append(items[i + 5])
				.append(",\"canceled\":false")
				.append('}');
		}
		sb.append("]}");
		return sb.toString();
	}

	private static JsonNode arrayOf(String... rows) {
		try {
			return MAPPER.readTree("[" + String.join(",", rows) + "]");
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private void stub(String status, JsonNode rows) {
		when(api.fetchOrders(any(), anyString(), anyString(), anyString()))
			.thenAnswer(inv -> status.equals(inv.getArgument(3)) ? rows : MAPPER.readTree("[]"));
	}

	private List<MarketOrderDto> fetch() {
		return adapter().fetchOrders(credential(), LocalDate.now().minusDays(3), LocalDate.now());
	}

	private static List<MarketLineItemDto> allItems(MarketOrderDto dto) {
		List<MarketLineItemDto> all = new ArrayList<>();
		dto.getShipments().forEach(s -> all.addAll(s.getLineItems()));
		return all;
	}

	private static MarketShipmentDto shipmentOf(MarketOrderDto dto, String boxId) {
		return dto.getShipments().stream()
			.filter(s -> boxId.equals(s.getMarketShipmentNo()))
			.findFirst().orElseThrow(() -> new AssertionError("배송 " + boxId + " 없음"));
	}
}

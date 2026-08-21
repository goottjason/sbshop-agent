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

/**
 * 3단계: 쿠팡 어댑터가 <b>(주문 / 배송 / 상품주문) 3계층</b>으로 정규화한다.
 *
 * <h2>라이브로 확인한 응답 구조 (2026-08-06, 8개월 407행)</h2>
 *
 * <p>응답 <b>행 하나가 배송박스 하나</b>이고, 그 안에 {@code orderItems[]}가 상품주문들이다.
 * 행 레벨에 {@code shipmentBoxId}·{@code status}·{@code invoiceNumber}·{@code deliveryCompanyName}·
 * {@code splitShipping}이 있고, 상품 레벨에 {@code vendorItemId}·{@code externalVendorSkuCode}·
 * {@code shippingCount}·{@code orderPrice}·{@code canceled}가 있다.
 *
 * <p><b>중요한 차이 — 진행상태가 배송 레벨이다.</b> 11번가는 상품주문마다 상태가 갈리지만
 * 쿠팡은 행(배송박스) 하나에 상태 하나다. 그래서 그 배송에 속한 라인아이템들이 상태를 공유한다.
 * 단, 상품별 {@code canceled} 플래그가 있어 <b>부분취소는 상품 단위로 표현된다.</b>
 *
 * <p><b>실측 기준으로 다품목 사례가 우리 상점에 없다</b>(8개월 407행 전부 1행·1상품). 즉 이 전환은
 * 회귀 위험이 낮은 대신, 다품목·분할배송 경로를 <b>실물로 검증할 수 없다</b>. 그래서 설계 §12-1의
 * 미확정("행이 배송박스 단위인가")을 단정하지 않고 <b>두 해석 모두에서 옳게</b> 동작하도록 만든다 —
 * 같은 {@code orderId}의 행이 여러 개면 배송 여러 개로, 한 행에 상품이 여러 개면 라인아이템 여러 개로.
 */
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

	/** 라이브 응답 행 하나. orderItems는 (vendorItemId, sku, 이름, 수량, 단가, canceled) 튜플로 준다. */
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
				.append(",\"canceled\":false") // 취소 케이스는 별도 테스트가 원문 JSON으로 만든다
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

	/** 지정한 status에만 행을 주고 나머지는 빈 배열. */
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
		// 종전 코드는 orderItems.get(0)만 읽어 2번째부터 완전히 유실됐다(D-130 실측).
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
		// 총액은 단가 x 수량 — 종전 계산 규칙을 상품주문 단위로 그대로 적용한다.
		assertThat(second.getTotalAmount()).isEqualByComparingTo("14000");
	}

	@Test
	@DisplayName("같은 주문번호에 행이 여러 개면 배송 여러 개로 묶인다 — 분할배송")
	void groupsMultipleRowsOfSameOrderIntoOneDto() {
		// 설계 12-1의 미확정 사항이다. 우리 데이터에는 사례가 없으나 splitShipping 필드가 있으므로
		// 일어날 수 있다. 여기서 DTO를 두 개 내보내면 MarketOrderUpsertDispatcher가 같은 주문을
		// 두 번 찾아 onExisting을 두 번 부르고 서로 덮어쓴다 — 11번가에서 겪은 바로 그 함정이다.
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
		// 분할배송은 한 상품의 수량을 여러 박스로 쪼갤 수 있다. vendorItemId만 키로 쓰면
		// uk_line_item_order_market_no(order_id, market_line_item_no)를 위반해 동기화가 통째로 실패한다.
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
		// 11번가와 다른 점이다. 쿠팡은 배송박스 하나에 status 하나다(라이브 확인).
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
		// 평면 필드에 "첫 상품주문"을 담으면 종전의 키메라 행이 되살아난다.
		assertThat(dto.getStatus()).isNull();
		assertThat(dto.getTrackingNo()).isNull();
		assertThat(dto.getMarketProductCode()).isNull();
		// 6단계: 배송박스번호는 배송이 갖는다. 주문 계층으로도 나르면 원본이 둘이 되고,
		// 분할배송에서 "대표 박스"가 나머지 박스를 가린다.
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
		// 11번가 2단계에서 배운 것: 식별자·상세가 없다고 주문을 드롭하면 조용히 사라진다.
		String json = "{\"orderId\":3000012345,\"shipmentBoxId\":77001122,"
			+ "\"orderedAt\":\"2026-07-30T02:10:28\","
			+ "\"receiver\":{\"name\":\"홍길동\",\"postCode\":\"07997\",\"addr1\":\"서울\",\"addr2\":\"1\"},"
			+ "\"orderer\":{\"name\":\"김주문\"},\"orderItems\":[]}";
		stub("ACCEPT", arrayOf(json));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getShipments()).hasSize(1);
		assertThat(allItems(result.get(0))).hasSize(1);
		// 상품주문 식별자를 모르므로 위조하지 않는다(D-131) — 매칭은 카디널리티가 한다(D-132).
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
}

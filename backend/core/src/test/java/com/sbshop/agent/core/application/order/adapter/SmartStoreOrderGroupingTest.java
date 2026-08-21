package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 5단계: N스토어 어댑터가 응답을 <b>주문 / 배송 / 상품주문</b> 3계층으로 낸다.
 *
 * <p>네이버 응답은 <b>상품주문 하나가 한 원소</b>다 — 같은 주문의 상품주문 2건은 서로 다른
 * 원소로 오고 각자 {@code order} 노드를 갖는다. 종전에는 그 원소를 그대로 주문 1건으로 냈고,
 * 그래서 주문 하나가 우리 DB에서 <b>두 행</b>이 됐다(설계 §9.2).
 *
 * <p>실측(2026-08-06, 라이브 22건 전수): {@code order.orderId} 22/22 · {@code packageNumber}
 * 22/22 · {@code expectedSettlementAmount} 22/22.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreOrderGroupingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private SmartStoreOrderApiPort apiPort;
	@Mock
	private SmartStoreStatusMapper statusMapper;

	private SmartStoreOrderAdapter adapter;

	@BeforeEach
	void setUp() {
		adapter = new SmartStoreOrderAdapter(apiPort, statusMapper);
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
		when(statusMapper.mapStatus(any())).thenReturn(ShippingStatus.PREPARING);
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	/**
	 * 상품주문 1건짜리 응답 원소. {@code orderId}가 null이면 order 노드에 넣지 않는다
	 * (그 경우의 폴백을 검증하기 위함).
	 */
	private ObjectNode entry(ArrayNode array, String orderId, String productOrderId,
		String packageNumber, String status, String settlement, String trackingNo) {
		ObjectNode entry = array.addObject();

		ObjectNode order = entry.putObject("order");
		order.put("orderDate", "2026-07-22T10:14:23.177+09:00");
		order.put("ordererName", "허경덕");
		order.put("ordererTel", "010-1234-5678");
		if (orderId != null) {
			order.put("orderId", orderId);
		}

		ObjectNode productOrder = entry.putObject("productOrder");
		productOrder.put("productOrderId", productOrderId);
		productOrder.put("productOrderStatus", status);
		productOrder.put("productName", "나우푸드 밀크시슬 " + productOrderId);
		productOrder.put("sellerProductCode", "220522IHB016");
		productOrder.put("quantity", 1);
		productOrder.put("totalPaymentAmount", "65200");
		productOrder.put("placeOrderStatus", "OK");
		if (packageNumber != null) {
			productOrder.put("packageNumber", packageNumber);
		}
		if (settlement != null) {
			productOrder.put("expectedSettlementAmount", settlement);
		}

		ObjectNode shippingAddress = productOrder.putObject("shippingAddress");
		shippingAddress.put("zipCode", "12345");
		shippingAddress.put("baseAddress", "서울시 강남구");
		shippingAddress.put("detailedAddress", "101동 101호");
		shippingAddress.put("name", "허경덕");
		shippingAddress.put("tel1", "010-1234-5678");

		ObjectNode delivery = entry.putObject("delivery");
		if (trackingNo != null) {
			delivery.put("trackingNumber", trackingNo);
			delivery.put("deliveryCompany", "CJGLS");
			delivery.put("deliveryStatus", "DELIVERING");
		}
		return entry;
	}

	private List<MarketOrderDto> fetchToday() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		return adapter.fetchOrders(credential(), today, today);
	}

	@Test
	@DisplayName("같은 orderId의 상품주문 2건은 주문 1건 · 라인아이템 2건으로 묶인다")
	void sameOrderId_becomesOneOrderWithTwoLineItems() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "2026072134143761", "2026072112554021", "2026072160001571", "PAYED", "72602", null);
		entry(array, "2026072134143761", "2026072112554022", "2026072160001571", "PAYED", "40000", null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		List<MarketOrderDto> result = fetchToday();

		assertThat(result).hasSize(1);
		MarketOrderDto dto = result.get(0);
		// 주문 키는 orderId다 — 상품주문번호가 아니다(설계 §9.2).
		assertThat(dto.getMarketOrderNo()).isEqualTo("2026072134143761");
		assertThat(dto.getShipments()).hasSize(1);
		assertThat(dto.getShipments().get(0).getLineItems())
			.extracting(MarketLineItemDto::getMarketLineItemNo)
			.containsExactly("2026072112554021", "2026072112554022");
	}

	@Test
	@DisplayName("packageNumber가 배송 식별자다 — 다르면 배송 2건으로 갈린다")
	void differentPackageNumber_splitsShipments() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "ORDER-1", "PO-1", "PKG-1", "DELIVERING", "1000", "111111111111");
		entry(array, "ORDER-1", "PO-2", "PKG-2", "DELIVERING", "2000", "222222222222");
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		List<MarketOrderDto> result = fetchToday();

		assertThat(result).hasSize(1);
		List<MarketShipmentDto> shipments = result.get(0).getShipments();
		assertThat(shipments).hasSize(2);
		assertThat(shipments).extracting(MarketShipmentDto::getMarketShipmentNo)
			.containsExactlyInAnyOrder("PKG-1", "PKG-2");
		// 송장은 배송에 붙는다 — 한 배송 = 한 송장.
		assertThat(shipments).extracting(MarketShipmentDto::getTrackingNo)
			.containsExactlyInAnyOrder("111111111111", "222222222222");
	}

	@Test
	@DisplayName("packageNumber가 없으면 상품주문번호를 배송 식별자로 쓴다(배송 없는 주문은 만들지 않는다)")
	void missingPackageNumber_fallsBackToProductOrderId() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "ORDER-1", "PO-1", null, "PAYED", "1000", null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		List<MarketOrderDto> result = fetchToday();

		assertThat(result.get(0).getShipments()).hasSize(1);
		assertThat(result.get(0).getShipments().get(0).getMarketShipmentNo()).isEqualTo("PO-1");
	}

	@Test
	@DisplayName("orderId가 없으면 상품주문번호로 폴백한다 — 주문을 드롭하지 않는다")
	void missingOrderId_fallsBackToProductOrderId() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, null, "2026072251442781", "PKG-1", "PAYED", "1000", null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		List<MarketOrderDto> result = fetchToday();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getMarketOrderNo()).isEqualTo("2026072251442781");
	}

	@Test
	@DisplayName("같은 상품주문이 여러 청크에 나오면 마지막(최신) 상태 하나만 남는다")
	void duplicateProductOrderAcrossChunks_keepsLatestOnly() {
		// 30일 창을 1일 단위로 훑으므로, 창 안에서 상태가 두 번 바뀐 상품주문은 두 청크에 모두 나온다.
		// 그대로 두면 라인아이템 키가 중복돼 uk_line_item_order_market_no 위반으로 동기화가 통째로 실패한다.
		ArrayNode first = MAPPER.createArrayNode();
		entry(first, "ORDER-1", "PO-1", "PKG-1", "PAYED", "1000", null);
		ArrayNode second = MAPPER.createArrayNode();
		entry(second, "ORDER-1", "PO-1", "PKG-1", "DELIVERING", "1000", "333333333333");

		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(first, second);

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		List<MarketOrderDto> result = adapter.fetchOrders(credential(), today.minusDays(1), today);

		assertThat(result).hasSize(1);
		List<MarketShipmentDto> shipments = result.get(0).getShipments();
		assertThat(shipments).hasSize(1);
		assertThat(shipments.get(0).getLineItems()).hasSize(1);
		// 나중 청크가 이긴다 — 최신 상태가 진실이다.
		assertThat(shipments.get(0).getTrackingNo()).isEqualTo("333333333333");
	}

	@Test
	@DisplayName("정산예정금액(expectedSettlementAmount)을 상품주문별 실측값으로 싣는다")
	void carriesExpectedSettlementAmount() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "ORDER-1", "PO-1", "PKG-1", "PAYED", "72602", null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		MarketLineItemDto lineItem = fetchToday().get(0).getShipments().get(0).getLineItems().get(0);

		assertThat(lineItem.getSettlementAmount()).isEqualByComparingTo(new BigDecimal("72602"));
	}

	@Test
	@DisplayName("발주확인·취소용으로 주문의 전체 상품주문번호를 marketSpecificData에 담는다")
	void carriesAllProductOrderIdsForOrderLevelApis() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "ORDER-1", "PO-1", "PKG-1", "PAYED", "1000", null);
		entry(array, "ORDER-1", "PO-2", "PKG-1", "PAYED", "2000", null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		// 구분자가 '|'인 이유: Order.marketSpecificData는 자체 구현 유사 JSON이고 읽을 때 ','로
		// split한다 — 콤마를 쓰면 값이 조용히 잘린다(D-135).
		assertThat(fetchToday().get(0).getMarketSpecificData())
			.containsEntry("productOrderIds", "PO-1|PO-2");
	}

	@Test
	@DisplayName("PAYMENT_WAITING(미결제)은 종전대로 제외한다")
	void paymentWaitingIsSkipped() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "ORDER-1", "PO-1", "PKG-1", "PAYMENT_WAITING", "0", null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		assertThat(fetchToday()).isEmpty();
	}
}

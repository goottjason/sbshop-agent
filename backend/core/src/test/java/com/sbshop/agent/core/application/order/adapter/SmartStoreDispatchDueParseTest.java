package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreDispatchDueParseTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private SmartStoreOrderApiPort smartStoreOrderApiPort;
	private SmartStoreOrderAdapter adapter;

	@BeforeEach
	void setUp() {
		adapter = new SmartStoreOrderAdapter(smartStoreOrderApiPort, new SmartStoreStatusMapper());
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	@Test
	@DisplayName("D-349: 발송기한(shippingDueDate)을 배송 단위로 넘긴다 — 지연 처리 전이면 사유는 비어 있다")
	void shippingDueDateIsCarriedOnShipment() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(response(null, null));

		MarketShipmentDto shipment = onlyShipment();

		assertThat(shipment.getDispatchDueAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 23, 59, 59));
		assertThat(shipment.getDispatchDelayReason()).isNull();
	}

	@Test
	@DisplayName("D-349: 판매자가 발송지연 처리하면 사유와 상세 사유를 함께 넘긴다")
	void delayedDispatchReasonIsCarriedOnShipment() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(response("PRODUCT_PREPARE", "해외 입고 지연"));

		MarketShipmentDto shipment = onlyShipment();

		assertThat(shipment.getDispatchDelayReason()).isEqualTo("PRODUCT_PREPARE: 해외 입고 지연");
	}

	@Test
	@DisplayName("D-349: 발송기한 형식이 이상해도 주문은 빠지지 않고 기한만 비운다")
	void malformedDueDateDoesNotDropOrder() {
		ArrayNode response = response(null, null);
		((ObjectNode) response.get(0).get("productOrder")).put("shippingDueDate", "2026/10/21");
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any())).thenReturn(response);

		assertThat(onlyShipment().getDispatchDueAt()).isNull();
	}

	private MarketShipmentDto onlyShipment() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		List<MarketOrderDto> result = adapter.fetchOrders(credential(), today, today);
		assertThat(result).hasSize(1);
		assertThat(result.get(0).getShipments()).hasSize(1);
		return result.get(0).getShipments().get(0);
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	private ArrayNode response(String delayReason, String delayDetail) {
		ArrayNode array = MAPPER.createArrayNode();
		ObjectNode entry = array.addObject();

		ObjectNode order = entry.putObject("order");
		order.put("orderId", "2026101800000001");
		order.put("orderDate", "2026-10-18T18:46:38.093+09:00");
		order.put("ordererName", "홍길동");
		order.put("ordererTel", "010-0000-0001");

		ObjectNode productOrder = entry.putObject("productOrder");
		productOrder.put("productOrderId", "2026101800000002");
		productOrder.put("packageNumber", "2026101800000003");
		productOrder.put("productOrderStatus", "PAYED");
		productOrder.put("placeOrderStatus", "CANCEL");
		productOrder.put("shippingDueDate", "2026-10-21T23:59:59.000+09:00");
		if (delayReason != null) {
			productOrder.put("delayedDispatchReason", delayReason);
			productOrder.put("delayedDispatchDetailedReason", delayDetail);
		}
		productOrder.put("productName", "테스트 상품");
		productOrder.put("quantity", 1);
		productOrder.put("totalPaymentAmount", "45700");

		ObjectNode shippingAddress = productOrder.putObject("shippingAddress");
		shippingAddress.put("zipCode", "00000");
		shippingAddress.put("baseAddress", "서울시");
		shippingAddress.put("detailedAddress", "1호");
		shippingAddress.put("name", "홍길동");
		shippingAddress.put("tel1", "010-0000-0001");

		entry.putObject("delivery");
		return array;
	}
}

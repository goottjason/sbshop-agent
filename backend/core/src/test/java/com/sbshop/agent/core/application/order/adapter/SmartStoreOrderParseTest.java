package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * D-117: 스마트스토어 주문 파싱이 선택 필드 누락으로 주문을 통째로 드롭하지 않는지 검증.
 * placeOrderStatus는 응답에 없을 수 있는데 Map.of는 null 값에 NPE를 던지고, 그 NPE를
 * parseOrderNode의 catch가 삼켜 해당 주문이 조용히 사라진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreOrderParseTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private SmartStoreOrderApiPort smartStoreOrderApiPort;
	@Mock
	private SmartStoreStatusMapper statusMapper;
	@InjectMocks
	private SmartStoreOrderAdapter adapter;

	@org.junit.jupiter.api.BeforeEach
	void speedUpDelays() {
		// D-118로 청크 간 지연이 생겼다 — 단위 테스트는 대기 없이 돌린다.
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	/** placeOrderStatus 필드를 아예 담지 않은 정상 주문 1건 응답 */
	private ArrayNode responseWithoutPlaceOrderStatus() {
		ArrayNode array = MAPPER.createArrayNode();
		ObjectNode entry = array.addObject();

		ObjectNode order = entry.putObject("order");
		order.put("orderDate", "2026-07-22T10:14:23.177+09:00");
		order.put("ordererName", "허경덕");
		order.put("ordererTel", "010-1234-5678");

		ObjectNode productOrder = entry.putObject("productOrder");
		productOrder.put("productOrderId", "2026072251442781");
		productOrder.put("productOrderStatus", "PAYED");
		productOrder.put("productName", "나우푸드 밀크시슬");
		productOrder.put("quantity", 1);
		productOrder.put("totalPaymentAmount", "65200");
		// placeOrderStatus 의도적으로 누락

		ObjectNode shippingAddress = productOrder.putObject("shippingAddress");
		shippingAddress.put("zipCode", "12345");
		shippingAddress.put("baseAddress", "서울시 강남구");
		shippingAddress.put("detailedAddress", "101동 101호");
		shippingAddress.put("name", "허경덕");
		shippingAddress.put("tel1", "010-1234-5678");

		entry.putObject("delivery");
		return array;
	}

	@Test
	@DisplayName("D-117: placeOrderStatus 누락 응답도 주문이 드롭되지 않고 파싱된다")
	void missingPlaceOrderStatus_orderIsNotDropped() {
		when(smartStoreOrderApiPort.fetchOrders(any(), any(), any(), any()))
			.thenReturn(responseWithoutPlaceOrderStatus());
		when(statusMapper.mapStatus(any())).thenReturn(ShippingStatus.NEW);

		LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
		List<MarketOrderDto> result = adapter.fetchOrders(credential(), today, today);

		assertThat(result).isNotEmpty();
		assertThat(result.get(0).getMarketOrderNo()).isEqualTo("2026072251442781");
	}
}

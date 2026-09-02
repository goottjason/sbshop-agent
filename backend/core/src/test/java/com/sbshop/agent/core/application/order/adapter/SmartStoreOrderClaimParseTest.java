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
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreOrderClaimParseTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private SmartStoreOrderApiPort apiPort;

	private SmartStoreOrderAdapter adapter;

	@BeforeEach
	void setUp() {
		adapter = new SmartStoreOrderAdapter(apiPort, new SmartStoreStatusMapper());
		adapter.chunkDelayMillis = 0L;
		adapter.retryBackoffMillis = 0L;
	}

	@Test
	@DisplayName("productOrder.claimType/claimStatus를 라인아이템 claim으로 파싱한다")
	void claimTypeAndStatusAreParsedIntoLineItem() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, "RETURN", "RETURN_REQUEST");
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		MarketLineItemDto lineItem = fetchToday().get(0).getShipments().get(0).getLineItems().get(0);

		assertThat(lineItem.getClaim().getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(lineItem.getClaim().getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
		assertThat(lineItem.getClaim().getClaimRawCode()).isEqualTo("RETURN_REQUEST");
	}

	@Test
	@DisplayName("claimType/claimStatus가 없으면 클레임 없음으로 파싱한다")
	void missingClaimFieldsMeanNoClaim() {
		ArrayNode array = MAPPER.createArrayNode();
		entry(array, null, null);
		when(apiPort.fetchOrders(any(), any(), any(), any())).thenReturn(array);

		MarketLineItemDto lineItem = fetchToday().get(0).getShipments().get(0).getLineItems().get(0);

		assertThat(lineItem.getClaim().getClaimType()).isEqualTo(ClaimType.NONE);
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getClientId()).thenReturn("clientId");
		when(c.getSecretKey()).thenReturn("secret");
		return c;
	}

	private ObjectNode entry(ArrayNode array, String claimType, String claimStatus) {
		ObjectNode entry = array.addObject();

		ObjectNode order = entry.putObject("order");
		order.put("orderDate", "2026-07-22T10:14:23.177+09:00");
		order.put("ordererName", "허경덕");
		order.put("ordererTel", "010-1234-5678");
		order.put("orderId", "ORDER-1");

		ObjectNode productOrder = entry.putObject("productOrder");
		productOrder.put("productOrderId", "PO-1");
		productOrder.put("productOrderStatus", "DELIVERING");
		productOrder.put("productName", "나우푸드 밀크시슬");
		productOrder.put("sellerProductCode", "220522IHB016");
		productOrder.put("quantity", 1);
		productOrder.put("totalPaymentAmount", "65200");
		productOrder.put("placeOrderStatus", "OK");
		productOrder.put("packageNumber", "PKG-1");
		if (claimType != null) {
			productOrder.put("claimType", claimType);
		}
		if (claimStatus != null) {
			productOrder.put("claimStatus", claimStatus);
		}

		ObjectNode shippingAddress = productOrder.putObject("shippingAddress");
		shippingAddress.put("zipCode", "12345");
		shippingAddress.put("baseAddress", "서울시 강남구");
		shippingAddress.put("detailedAddress", "101동 101호");
		shippingAddress.put("name", "허경덕");
		shippingAddress.put("tel1", "010-1234-5678");

		entry.putObject("delivery");
		return entry;
	}

	private List<MarketOrderDto> fetchToday() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		return adapter.fetchOrders(credential(), today, today);
	}
}

package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.service.ClaimOrphanRecorder;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangSettlementQueryTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Mock
	private CoupangOrderApiPort api;

	@Test
	@DisplayName("D-348: 정산액은 주문번호+옵션 단위다 — 같은 SKU의 두 주문이 한 값으로 뭉개지지 않는다")
	void keysByOrderAndVendorItem() {
		when(api.querySalesDetails(any(), anyString(), anyString())).thenReturn(json("["
			+ sale(1001, 0, item(555, "SKU-A", 25265)) + ","
			+ sale(1002, 0, item(555, "SKU-A", 50530)) + "]"));

		Map<String, BigDecimal> result = adapter().querySettlement(credential(),
			LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19));

		assertThat(result).hasSize(2);
		assertThat(result.get("1001:555")).isEqualByComparingTo("25265");
		assertThat(result.get("1002:555")).isEqualByComparingTo("50530");
	}

	@Test
	@DisplayName("D-348: 상품 1개 주문의 배송비 정산액은 그 상품에 더한다")
	void addsDeliveryFeeToSingleItemOrder() {
		when(api.querySalesDetails(any(), anyString(), anyString())).thenReturn(json("["
			+ sale(1001, 2700, item(555, "SKU-A", 25265)) + "]"));

		Map<String, BigDecimal> result = adapter().querySettlement(credential(),
			LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19));

		assertThat(result.get("1001:555")).isEqualByComparingTo("27965");
	}

	@Test
	@DisplayName("D-348: 환불(REFUND) 행은 판매 정산액에 섞지 않는다")
	void ignoresRefundRows() {
		when(api.querySalesDetails(any(), anyString(), anyString())).thenReturn(json("["
			+ "{\"orderId\":1001,\"saleType\":\"REFUND\",\"deliveryFee\":{\"settlementAmount\":-13538},\"items\":[]},"
			+ sale(1002, 0, item(555, "SKU-A", 25265)) + "]"));

		Map<String, BigDecimal> result = adapter().querySettlement(credential(),
			LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19));

		assertThat(result).containsOnlyKeys("1002:555");
	}

	@Test
	@DisplayName("D-348: 쿠팡은 1개월 이상 범위를 거부한다 — 30일 이하 구간으로 나눠 조회한다")
	void splitsRangeIntoChunksShorterThanAMonth() {
		when(api.querySalesDetails(any(), anyString(), anyString())).thenReturn(json("[]"));

		adapter().querySettlement(credential(), LocalDate.of(2026, 8, 22), LocalDate.of(2026, 10, 19));

		verify(api).querySalesDetails(any(), org.mockito.ArgumentMatchers.eq("2026-08-22"),
			org.mockito.ArgumentMatchers.eq("2026-09-20"));
		verify(api).querySalesDetails(any(), org.mockito.ArgumentMatchers.eq("2026-09-21"),
			org.mockito.ArgumentMatchers.eq("2026-10-19"));
	}

	@Test
	@DisplayName("D-348: 조회 실패는 삼키지 않는다 — 동기화가 실패로 기록돼야 한다")
	void propagatesApiFailure() {
		when(api.querySalesDetails(any(), anyString(), anyString()))
			.thenThrow(new IllegalStateException("Date range period must be less than 1 months"));

		assertThatThrownBy(() -> adapter().querySettlement(credential(),
			LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19)))
			.hasMessageContaining("less than 1 months");
	}

	private CoupangOrderAdapter adapter() {
		return new CoupangOrderAdapter(api, new CoupangStatusMapper(), mock(OrderRepository.class),
			mock(OrderLineItemRepository.class), mock(MarketRegistrationRepository.class),
			mock(ShipmentRepository.class), mock(ClaimOrphanRecorder.class));
	}

	private MarketCredential credential() {
		return mock(MarketCredential.class);
	}

	private static String sale(long orderId, long deliverySettlement, String... items) {
		return "{\"orderId\":" + orderId + ",\"saleType\":\"SALE\","
			+ "\"deliveryFee\":{\"settlementAmount\":" + deliverySettlement + "},"
			+ "\"items\":[" + String.join(",", items) + "]}";
	}

	private static String item(long vendorItemId, String sku, long settlement) {
		return "{\"vendorItemId\":" + vendorItemId + ",\"externalSellerSkuCode\":\"" + sku + "\","
			+ "\"quantity\":1,\"settlementAmount\":" + settlement + "}";
	}

	private static JsonNode json(String s) {
		try {
			return MAPPER.readTree(s);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}

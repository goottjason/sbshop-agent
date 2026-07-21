package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-097: 쿠팡 반품완료 전방 감지. 배송완료(DELIVERED) 주문이 쿠팡 returnRequests에서
 * RETURNS_COMPLETED로 확증되면 RETURNED + 정산 0 + verified로 전환됨을 고정한다.
 * absence 추론이 아니라 쿠팡 원본 확증 경로다.
 */
@ExtendWith(MockitoExtension.class)
class CoupangDetectReturnsTest {

	private static final ObjectMapper OM = new ObjectMapper();

	@Mock
	private CoupangOrderApiPort coupangOrderApiPort;
	@Mock
	private CoupangStatusMapper statusMapper;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@InjectMocks
	private CoupangOrderAdapter adapter;

	private final MarketCredential credential = mock(MarketCredential.class);

	private Order coupangOrder(String orderNo) {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo(orderNo)
			.orderDate(LocalDateTime.now().minusDays(3))
			.build();
	}

	private OrderLineItem deliveredItem(BigDecimal settlement) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(ShippingStatus.DELIVERED).build())
			.settlementData(SettlementData.builder().settlementAmount(settlement).settlementVerified(false).build())
			.build();
	}

	private OrderLineItem itemWithStatus(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	private JsonNode returns(String json) {
		try {
			return OM.readTree(json);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	@DisplayName("[D-097] returnRequests가 RETURNS_COMPLETED로 확증하면 DELIVERED→RETURNED+정산0")
	void completedReturn_transitionsDeliveredToReturnedWithZeroSettlement() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(item.getSettlementData().getSettlementVerified()).isTrue();
	}

	@Test
	@DisplayName("[D-097] 진행중 반품(RU 등 미완료)은 전환하지 않는다")
	void inProgressReturn_doesNotTransition() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		// 미완료 반품이면 어댑터가 조기 반환하므로 이 조회들은 호출되지 않는다(그게 올바른 동작) → lenient.
		lenient().when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		lenient().when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RELEASE_STOP_UNCHECKED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(new BigDecimal("63724.00"));
	}

	@Test
	@DisplayName("[D-097] DB에 없는 주문의 반품은 무시(예외 없음)")
	void returnForUnknownOrder_isNoOp() {
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of());
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":9999999999,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());
		// 예외 없이 통과하면 성공
	}

	@Test
	@DisplayName("[D-097] 이미 RETURNED인 주문은 멱등 — 그대로 유지")
	void alreadyReturned_isIdempotent() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = itemWithStatus(ShippingStatus.RETURNED);
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
	}
}

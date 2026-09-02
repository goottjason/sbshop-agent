package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangDetectReturnsTest {
	private static final ObjectMapper OM = new ObjectMapper();

	@Mock
	private CoupangOrderApiPort coupangOrderApiPort;
	@Spy
	private CoupangStatusMapper statusMapper = new CoupangStatusMapper();
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@InjectMocks
	private CoupangOrderAdapter adapter;

	private final MarketCredential credential = mock(MarketCredential.class);

	@Test
	@DisplayName("[D-097/D-270] 반품완료면 배송 단계는 마켓 값 그대로 두고 정산만 0으로 정규화한다")
	void completedReturn_zeroesSettlementWithoutOverwritingShippingStatus() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(item.getSettlementData().getSettlementVerified()).isTrue();
	}

	@Test
	@DisplayName("[D-097] 진행중 반품(RU 등 미완료)은 전환하지 않는다")
	void inProgressReturn_doesNotTransition() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
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
	}

	@Test
	@DisplayName("[D-270] 옛 shipping_status=RETURNED 레거시 데이터도 RefundTerminalPolicy로 정산0 대상이 된다")
	void legacyReturnedStatus_isTreatedAsRefundTerminal() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = itemWithStatus(ShippingStatus.RETURNED);
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.RETURNED);
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("[D-270] 같은 반품완료 응답을 두 번 처리해도 정산0 재기록이 없다 — 멱등")
	void completedReturn_processedTwice_doesNotRezeroSettlement() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = spy(deliveredItem(new BigDecimal("63724.00")));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());
		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		verify(item, times(1)).applySettlement(BigDecimal.ZERO);
		verify(item, times(1)).markSettlementVerified();
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("[D-270] 반품완료는 클레임에도 RETURN/DONE으로 기록된다")
	void completedReturn_recordsClaimData() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

	@Test
	@DisplayName("[D-270] 진행중 반품도 배송 단계는 지키되 클레임은 IN_PROGRESS로 기록한다")
	void inProgressReturn_stillRecordsClaimData() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"RETURN\",\"receiptStatus\":\"RELEASE_STOP_UNCHECKED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
	}

	@Test
	@DisplayName("[D-270] 취소(CANCEL) 클레임도 잡힌다 — 배송 단계는 건드리지 않는다")
	void cancelClaim_recordsClaimDataWithoutTouchingShippingStatus() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		when(coupangOrderApiPort.queryReturns(any(), any(), any())).thenReturn(returns(
			"[{\"orderId\":2101402034506,\"receiptType\":\"CANCEL\",\"receiptStatus\":\"RETURNS_COMPLETED\"}]"));

		adapter.detectReturns(credential, LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.CANCEL);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

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
}

package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.application.order.service.ClaimOrphanRecorder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
class CoupangDetectExchangesTest {
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
	@Mock
	private ClaimOrphanRecorder claimOrphanRecorder;
	@InjectMocks
	private CoupangOrderAdapter adapter;

	private final MarketCredential credential = mock(MarketCredential.class);

	@Test
	@DisplayName("[D-277] 진행중 교환은 배송 단계를 건드리지 않고 클레임만 IN_PROGRESS로 기록한다")
	void progressExchange_recordsClaimWithoutTouchingShippingStatus() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryExchanges(any(), any(), any())).thenReturn(exchanges(
			"[{\"orderId\":2101402034506,\"exchangeStatus\":\"PROGRESS\",\"collectStatus\":\"Collecting\"}]"));

		adapter.detectExchanges(credential, LocalDate.now().minusDays(7), LocalDate.now());

		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.IN_PROGRESS);
	}

	@Test
	@DisplayName("[D-277] 교환완료(SUCCESS)는 결제가 유지되므로 정산을 0으로 만들지 않는다")
	void successExchange_doesNotZeroSettlement() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryExchanges(any(), any(), any())).thenReturn(exchanges(
			"[{\"orderId\":2101402034506,\"exchangeStatus\":\"SUCCESS\"}]"));

		adapter.detectExchanges(credential, LocalDate.now().minusDays(7), LocalDate.now());

		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.DONE);
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(new BigDecimal("63724.00"));
	}

	@Test
	@DisplayName("[D-277] 거부(REJECT)·철회(CANCEL)도 REJECTED로 기록된다")
	void rejectedExchange_recordsRejectedClaim() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryExchanges(any(), any(), any())).thenReturn(exchanges(
			"[{\"orderId\":2101402034506,\"exchangeStatus\":\"REJECT\"}]"));

		adapter.detectExchanges(credential, LocalDate.now().minusDays(7), LocalDate.now());

		assertThat(item.getClaimData().getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(item.getClaimData().getClaimStage()).isEqualTo(ClaimStage.REJECTED);
	}

	@Test
	@DisplayName("[D-277] DB에 없는 주문의 교환은 무시(예외 없음)")
	void exchangeForUnknownOrder_isNoOp() {
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of());
		when(coupangOrderApiPort.queryExchanges(any(), any(), any())).thenReturn(exchanges(
			"[{\"orderId\":9999999999,\"exchangeStatus\":\"SUCCESS\"}]"));

		adapter.detectExchanges(credential, LocalDate.now().minusDays(7), LocalDate.now());
	}

	@Test
	@DisplayName("[D-277] 같은 교환 응답을 두 번 처리해도 정산은 그대로다 — 배송 단계도 멱등")
	void progressExchange_processedTwice_staysConsistent() {
		Order order = coupangOrder("2101402034506");
		OrderLineItem item = deliveredItem(new BigDecimal("63724.00"));
		when(orderRepository.findByMarketType(MarketType.COUPANG)).thenReturn(List.of(order));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
		when(coupangOrderApiPort.queryExchanges(any(), any(), any())).thenReturn(exchanges(
			"[{\"orderId\":2101402034506,\"exchangeStatus\":\"PROGRESS\"}]"));

		adapter.detectExchanges(credential, LocalDate.now().minusDays(7), LocalDate.now());
		adapter.detectExchanges(credential, LocalDate.now().minusDays(7), LocalDate.now());

		verify(orderLineItemRepository, times(2)).save(item);
		assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(item.getSettlementData().getSettlementAmount()).isEqualByComparingTo(new BigDecimal("63724.00"));
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

	private JsonNode exchanges(String json) {
		try {
			return OM.readTree(json);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}

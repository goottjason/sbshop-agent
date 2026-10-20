package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

@ExtendWith(MockitoExtension.class)
class CoupangSettlementSyncTest {
	@Mock
	MarketCredentialRepository credentialRepository;
	@Mock
	OrderRepository orderRepository;
	@Mock
	OrderLineItemRepository orderLineItemRepository;
	@Mock
	CoupangOrderAdapter coupangOrderAdapter;
	@Mock
	SyncStatusService syncStatusService;
	@Mock
	ActionLogService actionLogService;

	@InjectMocks
	CoupangOrderSyncService service;

	@BeforeEach
	void setUp() {
		MarketCredential cred = Mockito.mock(MarketCredential.class);
		lenient().when(cred.getClientId()).thenReturn("cid");
		lenient().when(cred.getAccessKey()).thenReturn("ak");
		lenient().when(cred.getSecretKey()).thenReturn("sk");
		when(syncStatusService.tryMarkRunning(any())).thenReturn(true);
		when(credentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.of(cred));
	}

	@Test
	@DisplayName("D-348: 주문번호+옵션으로 찾은 라인에 실정산액을 넣고 확정 표시한다 — 구매확정 상태도 대상이다")
	void appliesActualSettlementToMatchingLine() {
		Order order = order(10L, "1001");
		OrderLineItem line = line(10L, "777:555", ShippingStatus.CONFIRMED, "34710");
		when(coupangOrderAdapter.querySettlement(any(), any(), any()))
			.thenReturn(Map.of("1001:555", new BigDecimal("31234")));
		when(orderRepository.findByMarketOrderNo("1001")).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(10L)).thenReturn(List.of(line));

		service.syncCoupangSettlement();

		assertThat(line.getSettlementData().getSettlementAmount()).isEqualByComparingTo("31234");
		assertThat(line.getSettlementData().getSettlementVerified()).isTrue();
		verify(orderLineItemRepository).save(line);
	}

	@Test
	@DisplayName("D-348: 같은 주문의 다른 옵션 라인은 건드리지 않는다")
	void leavesOtherVendorItemUntouched() {
		Order order = order(10L, "1001");
		OrderLineItem other = line(10L, "777:999", ShippingStatus.DELIVERED, "40000");
		when(coupangOrderAdapter.querySettlement(any(), any(), any()))
			.thenReturn(Map.of("1001:555", new BigDecimal("31234")));
		when(orderRepository.findByMarketOrderNo("1001")).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(10L)).thenReturn(List.of(other));

		service.syncCoupangSettlement();

		assertThat(other.getSettlementData().getSettlementAmount()).isEqualByComparingTo("40000");
		verify(orderLineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-348: 반품·취소 종결 라인은 정산 0 정규화(D-098) 소관이라 덮어쓰지 않는다")
	void skipsRefundTerminalLine() {
		Order order = order(10L, "1001");
		OrderLineItem returned = line(10L, "777:555", ShippingStatus.RETURNED, "0");
		when(coupangOrderAdapter.querySettlement(any(), any(), any()))
			.thenReturn(Map.of("1001:555", new BigDecimal("31234")));
		when(orderRepository.findByMarketOrderNo("1001")).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(10L)).thenReturn(List.of(returned));

		service.syncCoupangSettlement();

		assertThat(returned.getSettlementData().getSettlementAmount()).isEqualByComparingTo("0");
		verify(orderLineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-348: 다른 마켓 주문번호와 겹쳐도 쿠팡 주문에만 적용한다")
	void ignoresNonCoupangOrder() {
		Order order = Order.builder().marketType(MarketType.SMART_STORE).marketOrderNo("1001").build();
		ReflectionTestUtils.setField(order, "id", 10L);
		when(coupangOrderAdapter.querySettlement(any(), any(), any()))
			.thenReturn(Map.of("1001:555", new BigDecimal("31234")));
		when(orderRepository.findByMarketOrderNo("1001")).thenReturn(Optional.of(order));

		service.syncCoupangSettlement();

		verify(orderLineItemRepository, never()).findByOrderId(any());
	}

	private static Order order(Long id, String marketOrderNo) {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo(marketOrderNo).build();
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}

	private static OrderLineItem line(Long orderId, String marketLineItemNo, ShippingStatus status,
		String settlement) {
		return OrderLineItem.builder()
			.orderId(orderId)
			.quantity(1)
			.marketLineItemNo(marketLineItemNo)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.settlementData(SettlementData.builder().settlementAmount(new BigDecimal(settlement)).build())
			.build();
	}
}

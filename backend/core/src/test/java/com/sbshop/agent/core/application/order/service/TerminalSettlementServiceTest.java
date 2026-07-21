package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-098: 취소·반품 종결 lineItem의 정산액을 0으로 정규화(마켓 무관·멱등).
 * 교환(EXCHANGED)은 결제가 유지되므로 대상이 아니다.
 */
@ExtendWith(MockitoExtension.class)
class TerminalSettlementServiceTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@InjectMocks
	private TerminalSettlementService service;

	private Order order() {
		return Order.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo("O-1")
			.orderDate(java.time.LocalDateTime.now())
			.build();
	}

	private OrderLineItem item(ShippingStatus status, BigDecimal settlement) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.settlementData(SettlementData.builder().settlementAmount(settlement).settlementVerified(false).build())
			.build();
	}

	private void stubSingle(OrderLineItem item) {
		when(orderRepository.findByMarketType(any())).thenReturn(List.of(order()));
		when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of(item));
		lenient().when(orderLineItemRepository.save(any())).thenReturn(item);
	}

	@Test
	@DisplayName("[D-098] RETURNED lineItem의 정산액을 0으로 내리고 verified 표시")
	void returned_zeroed() {
		OrderLineItem it = item(ShippingStatus.RETURNED, new BigDecimal("26611.00"));
		stubSingle(it);

		int n = service.zeroSettlementForRefunded(MarketType.GMARKET);

		assertThat(n).isEqualTo(1);
		assertThat(it.getSettlementData().getSettlementAmount()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(it.getSettlementData().getSettlementVerified()).isTrue();
	}

	@Test
	@DisplayName("[D-098] CANCELED lineItem의 정산액을 0으로 내린다")
	void canceled_zeroed() {
		OrderLineItem it = item(ShippingStatus.CANCELED, new BigDecimal("78140.00"));
		stubSingle(it);

		int n = service.zeroSettlementForRefunded(MarketType.SMART_STORE);

		assertThat(n).isEqualTo(1);
		assertThat(it.getSettlementData().getSettlementAmount()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("[D-098] EXCHANGED는 결제 유지 — 정산액을 건드리지 않는다")
	void exchanged_untouched() {
		OrderLineItem it = item(ShippingStatus.EXCHANGED, new BigDecimal("50000.00"));
		stubSingle(it);

		int n = service.zeroSettlementForRefunded(MarketType.GMARKET);

		assertThat(n).isZero();
		assertThat(it.getSettlementData().getSettlementAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
		verify(orderLineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("[D-098] DELIVERED 등 정상 진행 상태는 정산액을 건드리지 않는다")
	void delivered_untouched() {
		OrderLineItem it = item(ShippingStatus.DELIVERED, new BigDecimal("30000.00"));
		stubSingle(it);

		int n = service.zeroSettlementForRefunded(MarketType.GMARKET);

		assertThat(n).isZero();
		assertThat(it.getSettlementData().getSettlementAmount()).isEqualByComparingTo(new BigDecimal("30000.00"));
		verify(orderLineItemRepository, never()).save(any());
	}

	@Test
	@DisplayName("[D-098] 이미 0인 취소 건은 멱등 — 저장하지 않는다")
	void alreadyZero_idempotent() {
		OrderLineItem it = item(ShippingStatus.CANCELED, BigDecimal.ZERO);
		stubSingle(it);

		int n = service.zeroSettlementForRefunded(MarketType.SMART_STORE);

		assertThat(n).isZero();
		verify(orderLineItemRepository, never()).save(any());
	}
}

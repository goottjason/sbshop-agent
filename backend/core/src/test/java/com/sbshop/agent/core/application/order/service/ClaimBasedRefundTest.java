package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimBasedRefundTest {

	@Mock
	OrderRepository orderRepository;
	@Mock
	OrderLineItemRepository orderLineItemRepository;

	@InjectMocks
	TerminalSettlementService service;

	private OrderLineItem item(ShippingStatus status, ClaimData claim) {
		return OrderLineItem.builder().orderId(1L).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.claimData(claim)
			.settlementData(SettlementData.builder().settlementAmount(new BigDecimal("10000")).build())
			.build();
	}

	@Test
	@DisplayName("배송단계는 배송완료여도 반품 완료 클레임이면 정산을 0 으로 만든다")
	void zeroesByClaimNotByShippingStatus() {
		OrderLineItem li = item(ShippingStatus.DELIVERED, ClaimData.builder()
			.claimType(ClaimType.RETURN).claimStage(ClaimStage.DONE).build());
		when(orderLineItemRepository.findAll()).thenReturn(List.of(li));
		when(orderRepository.findByMarketType(any())).thenReturn(List.of());

		assertThat(li.isRefundTerminal()).isTrue();
	}

	@Test
	@DisplayName("교환 완료는 정산을 건드리지 않는다 — 결제가 유지된다")
	void exchangeKeepsSettlement() {
		OrderLineItem li = item(ShippingStatus.SHIPPED, ClaimData.builder()
			.claimType(ClaimType.EXCHANGE).claimStage(ClaimStage.DONE).build());

		assertThat(li.isRefundTerminal()).isFalse();
	}

	@Test
	@DisplayName("옛 데이터처럼 shipping_status 에만 RETURNED 가 남아 있어도 환불로 본다 — 이전 과도기를 견딘다")
	void legacyShippingStatusStillCounts() {
		OrderLineItem li = item(ShippingStatus.RETURNED, ClaimData.builder().build());

		assertThat(RefundTerminalPolicy.isRefundTerminal(li)).isTrue();
	}

	@Test
	@DisplayName("새 클레임 필드만 있어도 환불로 본다")
	void newClaimFieldCounts() {
		OrderLineItem li = item(ShippingStatus.DELIVERED, ClaimData.builder()
			.claimType(ClaimType.CANCEL).claimStage(ClaimStage.DONE).build());

		assertThat(RefundTerminalPolicy.isRefundTerminal(li)).isTrue();
	}

	@Test
	@DisplayName("둘 다 없으면 환불이 아니다")
	void neitherIsNotRefund() {
		OrderLineItem li = item(ShippingStatus.DELIVERED, ClaimData.builder().build());

		assertThat(RefundTerminalPolicy.isRefundTerminal(li)).isFalse();
	}
}

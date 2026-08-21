package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;

/**
 * SP-E Final-review fix: cancelOrderToMarketplace는 cred가 null이어도
 * 포트에 위임해야 한다(Cafe24 기반 G마켓/옥션 silent no-op 제거 검증).
 */
@ExtendWith(MockitoExtension.class)
class MarketplaceShippingServiceCancelTest {

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private MarketOrderPort gmarketPort;

	private MarketplaceShippingService service() {
		when(gmarketPort.getMarketType()).thenReturn(MarketType.GMARKET);
		return new MarketplaceShippingService(orderRepository, credentialRepository, List.of(gmarketPort));
	}

	private Order orderOf(MarketType marketType) {
		return Order.builder()
			.marketType(marketType)
			.marketOrderNo("ORD-" + marketType.name())
			.build();
	}

	@Test
	@DisplayName("G마켓 cred 없어도 포트에 취소 위임 — silent no-op 방지")
	void cancelOrderToMarketplace_nullCred_stillDelegatesToPort() {
		Order order = orderOf(MarketType.GMARKET);
		when(credentialRepository.findByMarketType(MarketType.GMARKET)).thenReturn(Optional.empty());

		service().cancelOrderToMarketplace(order);

		verify(gmarketPort).cancelOrder(isNull(), eq(order));
	}
}

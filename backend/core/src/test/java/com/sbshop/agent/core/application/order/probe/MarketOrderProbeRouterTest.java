package com.sbshop.agent.core.application.order.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class MarketOrderProbeRouterTest {

	private final Order order = mock(Order.class);

	private MarketOrderProbe probeFor(List<MarketType> types, OrderProbeResult result) {
		MarketOrderProbe probe = mock(MarketOrderProbe.class);
		when(probe.marketTypes()).thenReturn(types);
		when(probe.probe(order)).thenReturn(result);
		return probe;
	}

	@Test
	@DisplayName("등록된 마켓은 해당 프로브로 라우팅한다")
	void routesToRegisteredProbe() {
		MarketOrderProbeRouter router = new MarketOrderProbeRouter(
			List.of(probeFor(List.of(MarketType.COUPANG), OrderProbeResult.found(ShippingStatus.DELIVERED))));

		assertThat(router.has(MarketType.COUPANG)).isTrue();
		assertThat(router.probe(MarketType.COUPANG, order).status()).isEqualTo(OrderProbeStatus.FOUND);
		assertThat(router.probe(MarketType.COUPANG, order).shippingStatus()).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("한 프로브가 여러 마켓을 담당할 수 있다 — 카페24 하나가 G마켓과 옥션을 맡는다")
	void oneProbeCoversManyMarkets() {
		MarketOrderProbeRouter router = new MarketOrderProbeRouter(List.of(
			probeFor(List.of(MarketType.GMARKET, MarketType.AUCTION),
				OrderProbeResult.found(ShippingStatus.SHIPPED))));

		assertThat(router.has(MarketType.GMARKET)).isTrue();
		assertThat(router.has(MarketType.AUCTION)).isTrue();
		assertThat(router.probe(MarketType.AUCTION, order).shippingStatus()).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("프로브가 없는 마켓은 UNKNOWN 을 돌려주고 상태를 만들지 않는다")
	void unknownWhenNoProbe() {
		MarketOrderProbeRouter router = new MarketOrderProbeRouter(List.of());

		assertThat(router.has(MarketType.SMART_STORE)).isFalse();
		OrderProbeResult result = router.probe(MarketType.SMART_STORE, order);
		assertThat(result.status()).isEqualTo(OrderProbeStatus.UNKNOWN);
		assertThat(result.shippingStatus()).isNull();
	}
}

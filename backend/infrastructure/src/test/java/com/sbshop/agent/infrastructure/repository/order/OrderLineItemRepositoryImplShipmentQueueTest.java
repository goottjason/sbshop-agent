package com.sbshop.agent.infrastructure.repository.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderLineItemRepositoryImplShipmentQueueTest {

	private final OrderLineItemRepositoryImpl repo = new OrderLineItemRepositoryImpl(null);

	@Test
	@DisplayName("배송 큐 조건은 trackingSentToMarket을 게이트로 쓰지 않는다")
	void shipmentEmailNeeded_doesNotGateOnTrackingSentToMarket() {
		String predicate = repo.shipmentEmailNeeded().toString();

		assertThat(predicate).doesNotContain("trackingSentToMarket");
	}

	@Test
	@DisplayName("배송 큐는 종결 전 상태(PREPARING·DISPATCHED·SHIPPED)를 모두 담는다")
	void shipmentEmailNeeded_coversAllPreTerminalStatuses() {
		String predicate = repo.shipmentEmailNeeded().toString();

		assertThat(predicate).contains("PREPARING");
		assertThat(predicate).contains("DISPATCHED");
		assertThat(predicate).contains("SHIPPED");
	}
}

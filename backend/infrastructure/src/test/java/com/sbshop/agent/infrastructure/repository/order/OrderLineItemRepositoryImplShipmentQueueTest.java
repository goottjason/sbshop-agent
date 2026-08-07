package com.sbshop.agent.infrastructure.repository.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-144: 배송 큐 조건이 <b>마켓이 송장을 갖고 있다</b>를 <b>우리가 진짜 송장을 확보했다</b>로
 * 착각하던 결함의 회귀 테스트.
 *
 * <p>마켓에서 유입된 가송장(라이브 실측: 무관한 두 주문에 같은 번호 `363092185283`)이
 * {@code trackingSentToMarket=true}를 만들면 그 주문은 큐에서 빠지고, <b>그 주문번호로 메일을
 * 검색조차 하지 않는다.</b> 진짜 발송메일이 도착해도 영영 교정되지 않았다(활성 16건 중 12건이
 * DB 송장 ≠ 발송메일 송장).
 *
 * <p>교정 자체는 {@code EmailFetcherService.processIherbShipment}에 이미 있다 — 큐에만 들어오면 된다.
 */
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

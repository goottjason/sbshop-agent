package com.sbshop.agent.infrastructure.repository.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실구매가(sourcing_amount) 미기록 iHerb 라인아이템 조회 조건.
 * 기존 findIherbItemsNeedingEmailProcessing 은 배송 큐(PREPARING/미동기 DISPATCHED·SHIPPED) 전용이라
 * 배송이 끝난 주문은 확인메일 검색 대상에서 영구 제외됐다 — 실구매가 조회는 별도 조건이어야 한다.
 */
class OrderLineItemRepositoryImplPurchaseAmountTest {

	private final OrderLineItemRepositoryImpl repo = new OrderLineItemRepositoryImpl(null);

	@Test
	@DisplayName("실구매가 미기록 조건은 배송상태를 참조하지 않는다")
	void purchaseAmountMissing_doesNotDependOnShippingStatus() {
		String predicate = repo.purchaseAmountMissing().toString();

		assertThat(predicate).doesNotContain("shippingStatus");
	}

	@Test
	@DisplayName("실구매가 미기록 조건은 null 과 0 을 모두 대상으로 삼는다")
	void purchaseAmountMissing_coversNullAndZero() {
		String predicate = repo.purchaseAmountMissing().toString();

		assertThat(predicate).contains("sourcingAmount");
		assertThat(predicate).containsIgnoringCase("null");
		assertThat(predicate).contains("0");
	}
}

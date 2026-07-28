package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.List;

public interface OrderLineItemRepositoryCustom {
	List<Long> findProductIdsByShippingStatus(ShippingStatus status);

	/** 이메일 처리가 필요한 iHerb 라인아이템 조회 (PURCHASED 또는 SHIPPED+미동기화) */
	List<OrderLineItem> findIherbItemsNeedingEmailProcessing();

	/**
	 * 실구매가(sourcing_amount)가 아직 기록되지 않은 iHerb 라인아이템 조회.
	 * 배송상태와 무관하다 — 확인메일에서 실구매가를 받는 일은 송장 처리와 생애주기가 다르므로
	 * 배송 큐({@link #findIherbItemsNeedingEmailProcessing})와 분리해야 배송완료 주문도 대상이 된다.
	 */
	List<OrderLineItem> findIherbItemsNeedingPurchaseAmount();
}

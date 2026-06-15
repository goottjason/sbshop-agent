package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.List;

public interface OrderLineItemRepositoryCustom {
	List<Long> findProductIdsByShippingStatus(ShippingStatus status);

	/** 이메일 처리가 필요한 iHerb 라인아이템 조회 (PURCHASED 또는 SHIPPED+미동기화) */
	List<OrderLineItem> findIherbItemsNeedingEmailProcessing();
}

package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.List;

public interface OrderLineItemRepositoryCustom {
	List<Long> findProductIdsByShippingStatus(ShippingStatus status);

	List<OrderLineItem> findIherbItemsNeedingEmailProcessing();

	List<OrderLineItem> findIherbItemsNeedingPurchaseAmount();
}

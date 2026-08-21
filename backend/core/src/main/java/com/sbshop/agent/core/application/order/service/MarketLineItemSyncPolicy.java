package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import java.math.BigDecimal;

public interface MarketLineItemSyncPolicy {
	String logTag();

	Long resolveProductId(MarketLineItemDto dto);

	OrderLineItem createLineItem(MarketLineItemDto dto, Long orderId, Long productId);

	BigDecimal settlementAmount(MarketLineItemDto dto);
}

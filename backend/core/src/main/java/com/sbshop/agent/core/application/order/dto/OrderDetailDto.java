package com.sbshop.agent.core.application.order.dto;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.product.Product;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderDetailDto {
	private Order order;
	private List<OrderLineItemDetailDto> lineItems;

	@Data
	@Builder
	public static class OrderLineItemDetailDto {
		private OrderLineItem lineItem;
		private Product product;
		private MarketRegistration marketRegistration;
	}
}

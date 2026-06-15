package com.sbshop.agent.core.domain.order.dto;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.product.Product;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderGridDto {
	private Order order;
	private OrderLineItem lineItem;
	private Product product;
}

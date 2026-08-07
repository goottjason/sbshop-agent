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
		/**
		 * 이 상품주문이 속한 배송. 화면이 <b>마켓이 아는 송장</b>({@code marketTrackingNo})과
		 * 실제 송장의 불일치를 보고 "마켓 미반영"을 판정한다(D-148). 배송이 없는 레거시 행은 null.
		 */
		private com.sbshop.agent.core.domain.order.Shipment shipment;
	}
}

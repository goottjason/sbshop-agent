package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderLineItemResponse {

	private final Long id;
	private final RecordStatus status;
	private final LocalDateTime createdAt;
	private final LocalDateTime updatedAt;

	private final Long orderId;
	private final Long productId;
	private final Integer quantity;
	private final SourcingData sourcingData;
	private final SettlementData settlementData;
	private final ShippingData shippingData;
	private final Boolean isUnipassDone;
	private final PurchaseStatus purchaseStatus;

	private final String marketLineItemNo;
	private final Long shipmentId;

	private final boolean progressed;

	public static OrderLineItemResponse from(OrderLineItem item) {
		return OrderLineItemResponse.builder()
			.id(item.getId())
			.status(item.getStatus())
			.createdAt(item.getCreatedAt())
			.updatedAt(item.getUpdatedAt())
			.orderId(item.getOrderId())
			.productId(item.getProductId())
			.quantity(item.getQuantity())
			.sourcingData(item.getSourcingData())
			.settlementData(item.getSettlementData())
			.shippingData(item.getShippingData())
			.isUnipassDone(item.getIsUnipassDone())
			.purchaseStatus(item.getPurchaseStatus())
			.marketLineItemNo(item.getMarketLineItemNo())
			.shipmentId(item.getShipmentId())
			.progressed(item.isProgressed())
			.build();
	}
}

package com.sbshop.agent.api.dto;

import java.time.LocalDateTime;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

import lombok.Builder;
import lombok.Getter;

/**
 * OrderLineItem 쓰기 응답 DTO (SP-5 / F-ORD-28).
 *
 * <p>도메인 엔티티를 API 응답으로 직접 노출하지 않기 위한 경계 DTO. 프론트 계약을 보존하기 위해
 * 현재 {@link OrderLineItem} 엔티티가 직렬화되는 JSON 형태(파생 getter {@code isProgressed} 포함)를
 * 그대로 미러한다. (검증: {@code OrderResponseContractTest}).
 */
@Getter
@Builder
public class OrderLineItemResponse {

	// BaseEntity 유래 필드
	private final Long id;
	private final RecordStatus status;
	private final LocalDateTime createdAt;
	private final LocalDateTime updatedAt;

	// OrderLineItem 필드
	private final Long orderId;
	private final Long productId;
	private final Integer quantity;
	private final SourcingData sourcingData;
	private final SettlementData settlementData;
	private final ShippingData shippingData;
	private final Boolean isUnipassDone;
	private final PurchaseStatus purchaseStatus;

	/**
	 * 묶음배송·다품목 주문 모델 1단계 신설 컬럼 미러(설계:
	 * docs/superpowers/specs/2026-08-05-bundle-shipment-order-model-design.md).
	 * 1단계에서는 배선 전이라 항상 null이며, 2단계 이후 배송 묶음 표시
	 * (같은 shipmentId끼리 그리드에서 묶어 보여주기)에 쓰인다.
	 */
	private final String marketLineItemNo;
	private final Long shipmentId;

	// 엔티티의 파생 getter(isProgressed)도 현재 JSON에 포함되므로 미러
	private final boolean progressed;

	/** 엔티티를 응답 DTO로 매핑. 현재 직렬화 형태를 그대로 보존한다. */
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

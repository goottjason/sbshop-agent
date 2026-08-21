package com.sbshop.agent.core.domain.order;

import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_order_line_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLineItem extends BaseEntity {
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "product_id")
	private Long productId;

	@Column(name = "quantity", nullable = false)
	private Integer quantity;

	@Embedded
	private SourcingData sourcingData = SourcingData.builder().build();

	@Embedded
	private SettlementData settlementData = SettlementData.builder().build();

	@Embedded
	private ShippingData shippingData = ShippingData.builder().build();

	@Column(name = "is_unipass_done")
	private Boolean isUnipassDone;

	@Column(name = "purchase_status")
	@Enumerated(EnumType.STRING)
	private PurchaseStatus purchaseStatus = PurchaseStatus.NOT_PURCHASED;

	@Column(name = "market_line_item_no", length = 100)
	private String marketLineItemNo;

	@Column(name = "shipment_id")
	private Long shipmentId;

	@Builder
	public OrderLineItem(Long orderId, Long productId, Integer quantity, SourcingData sourcingData,
		SettlementData settlementData, ShippingData shippingData, Boolean isUnipassDone,
		PurchaseStatus purchaseStatus, String marketLineItemNo, Long shipmentId) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = quantity;
		this.sourcingData = sourcingData != null ? sourcingData : SourcingData.builder().build();
		this.settlementData = settlementData != null ? settlementData : SettlementData.builder().build();
		this.shippingData = shippingData != null ? shippingData : ShippingData.builder().build();
		this.isUnipassDone = isUnipassDone;
		this.purchaseStatus = purchaseStatus != null ? purchaseStatus : PurchaseStatus.NOT_PURCHASED;
		this.marketLineItemNo = marketLineItemNo;
		this.shipmentId = shipmentId;
	}

	public void markAsShipped() {
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(ShippingStatus.SHIPPED)
			.build();
	}

	public void markAsDispatched() {
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(ShippingStatus.DISPATCHED)
			.build();
	}

	public void markTrackingAsSent() {
		this.shippingData = this.shippingData.toBuilder()
			.trackingSentToMarket(true)
			.build();
	}

	public void applyShippingData(ShippingData data) {
		this.shippingData = data;
	}

	public void applySourcingData(SourcingData data) {
		this.sourcingData = data;
	}

	public void applySettlement(BigDecimal settlementAmount) {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementAmount(settlementAmount)
			.build();
	}

	public void recoverSettlement(BigDecimal settlementAmount) {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementAmount(settlementAmount)
			.settlementVerified(false)
			.build();
	}

	public void updateUnipassDone(Boolean isUnipassDone) {
		this.isUnipassDone = isUnipassDone;
	}

	public void updatePurchaseStatus(PurchaseStatus purchaseStatus) {
		this.purchaseStatus = purchaseStatus;
	}

	public boolean isProgressed() {
		ShippingStatus s = this.shippingData != null ? this.shippingData.getShippingStatus() : null;
		if (s == null || s == ShippingStatus.UNKNOWN)
			return false;
		if (s.getOrder() < 0)
			return false;
		return s.getOrder() >= ShippingStatus.PREPARING.getOrder();
	}

	public void assignProductId(Long productId) {
		this.productId = productId;
	}

	public void assignMarketLineItemNo(String marketLineItemNo) {
		this.marketLineItemNo = marketLineItemNo;
	}

	public void assignShipmentId(Long shipmentId) {
		this.shipmentId = shipmentId;
	}

	public void markSettlementVerified() {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementVerified(true)
			.build();
	}

	protected void assignOrderId(Long orderId) {
		this.orderId = orderId;
	}
}

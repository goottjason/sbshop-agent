package com.sbshop.agent.core.domain.order;

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

	/** 주문 ID (Order 테이블 참조값) */
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	/** SB 상품 ID (sb_product 참조) */
	@Column(name = "product_id")
	private Long productId;

	/** 주문 수량 */
	@Column(name = "quantity", nullable = false)
	private Integer quantity;

	/** 소싱 관련 데이터 (원가, 소싱처 등) */
	@jakarta.persistence.Embedded
	private SourcingData sourcingData = SourcingData.builder().build();

	/** 정산 데이터 (마켓수수료, 순이익 등) */
	@jakarta.persistence.Embedded
	private SettlementData settlementData = SettlementData.builder().build();

	/** 배송 관련 데이터 (운송장 번호, 배송상태 등) */
	@jakarta.persistence.Embedded
	private ShippingData shippingData = ShippingData.builder().build();

	/** 유니패스 신고 완료 여부 */
	@Column(name = "is_unipass_done")
	private Boolean isUnipassDone;

	/** 내부 구매 처리 상태 (마켓 동기화와 무관) */
	@Column(name = "purchase_status")
	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	private PurchaseStatus purchaseStatus = PurchaseStatus.NOT_PURCHASED;

	@Builder
	public OrderLineItem(Long orderId, Long productId, Integer quantity, SourcingData sourcingData,
		SettlementData settlementData, ShippingData shippingData, Boolean isUnipassDone,
		PurchaseStatus purchaseStatus) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = quantity;
		this.sourcingData = sourcingData != null ? sourcingData : SourcingData.builder().build();
		this.settlementData = settlementData != null ? settlementData : SettlementData.builder().build();
		this.shippingData = shippingData != null ? shippingData : ShippingData.builder().build();
		this.isUnipassDone = isUnipassDone;
		this.purchaseStatus = purchaseStatus != null ? purchaseStatus : PurchaseStatus.NOT_PURCHASED;
	}

	// ======================== 상태 변경 ========================

	/** 배송 시작으로 변경 */
	public void markAsShipped() {
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(ShippingStatus.SHIPPED)
			.build();
	}

	/** 배송지시로 변경 (마켓에 송장 전송 성공 시) */
	public void markAsDispatched() {
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(ShippingStatus.DISPATCHED)
			.build();
	}

	/** 마켓 송장 전송 완료 플래그 */
	public void markTrackingAsSent() {
		this.shippingData = this.shippingData.toBuilder()
			.trackingSentToMarket(true)
			.build();
	}

	// ======================== 데이터 갱신 ========================

	/** 배송정보 갱신 */
	public void applyShippingData(ShippingData data) {
		this.shippingData = data;
	}

	/** 소싱 정보 갱신 */
	public void applySourcingData(SourcingData data) {
		this.sourcingData = data;
	}

	/** 정산 정보 갱신 */
	public void applySettlement(BigDecimal settlementAmount) {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementAmount(settlementAmount)
			.build();
	}

	/** 유니패스 신고 완료 여부 변경 */
	public void updateUnipassDone(Boolean isUnipassDone) {
		this.isUnipassDone = isUnipassDone;
	}

	/** 내부 구매 상태 변경 (마켓 동기화와 무관) */
	public void updatePurchaseStatus(PurchaseStatus purchaseStatus) {
		this.purchaseStatus = purchaseStatus;
	}

	// ======================== 판단 ========================

	/** 발주확인 이후 진행상태 여부 (address 보호 판단용) */
	public boolean isProgressed() {
		ShippingStatus s = this.shippingData != null ? this.shippingData.getShippingStatus() : null;
		if (s == null || s == ShippingStatus.UNKNOWN)
			return false;
		if (s.getOrder() < 0)
			return false;
		return s.getOrder() >= ShippingStatus.PREPARING.getOrder();
	}

	// ======================== 하위 호환 ========================

	protected void assignOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public void assignProductId(Long productId) {
		this.productId = productId;
	}

	public void markSettlementVerified() {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementVerified(true)
			.build();
	}
}

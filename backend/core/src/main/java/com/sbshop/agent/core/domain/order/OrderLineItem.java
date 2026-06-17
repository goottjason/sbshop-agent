package com.sbshop.agent.core.domain.order;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.SettlementData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

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

	@Builder
	public OrderLineItem(Long orderId, Long productId, Integer quantity, SourcingData sourcingData,
		SettlementData settlementData, ShippingData shippingData) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = quantity;
		this.sourcingData = sourcingData != null ? sourcingData : SourcingData.builder().build();
		this.settlementData = settlementData != null ? settlementData : SettlementData.builder().build();
		this.shippingData = shippingData != null ? shippingData : ShippingData.builder().build();
	}

	/* ----- 배송정보 통합 갱신 (updateShipping + updateShippingWithCarrier 대체) ----- */
	public void updateShippingInfo(
		String trackingNo, ShippingStatus status, Boolean isUnipassDone, ShippingCarrier carrier,
		Boolean trackingSentToMarket) {
		ShippingData.ShippingDataBuilder builder = (this.shippingData != null) ? this.shippingData.toBuilder()
			: ShippingData.builder();
		if (trackingNo != null)
			builder.trackingNo(trackingNo);
		if (status != null)
			builder.shippingStatus(status);
		if (isUnipassDone != null)
			builder.isUnipassDone(isUnipassDone);
		if (carrier != null)
			builder.shippingCarrier(carrier);
		if (trackingSentToMarket != null)
			builder.trackingSentToMarket(trackingSentToMarket);
		this.shippingData = builder.build();
	}

	/* ----- 발주확인 이후 진행상태 여부 (address 보호 판단용) ----- */
	public boolean isProgressed() {
		ShippingStatus s = this.shippingData != null ? this.shippingData.getShippingStatus() : null;
		if (s == null || s == ShippingStatus.UNKNOWN)
			return false;
		if (s.getOrder() < 0)
			return false;
		return s.getOrder() >= ShippingStatus.PREPARING.getOrder();
	}

	/* ----- 기존 하위 호환 유지 메서드들 ----- */
	protected void assignOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public void assignProductId(Long productId) {
		this.productId = productId;
	}

	public void updateSettlement(BigDecimal settlementAmount) {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementAmount(settlementAmount)
			.build();
	}

	public void markSettlementVerified() {
		this.settlementData = (this.settlementData != null ? this.settlementData.toBuilder() : SettlementData.builder())
			.settlementVerified(true)
			.build();
	}

	public void updateSourcingData(SourcingData sourcingData) {
		this.sourcingData = sourcingData;
	}

	public void updateSettlementData(SettlementData settlementData) {
		this.settlementData = settlementData;
	}

	public void updateShippingData(ShippingData shippingData) {
		this.shippingData = shippingData;
	}

	public void updateSourcingForIherb(String account, String orderNo, String discountCode) {
		this.sourcingData = SourcingData.builder()
			.sourcingVendor("IHB")
			.sourcingAccount(account)
			.sourcingOrderNo(orderNo)
			.discountCode(discountCode)
			.build();
	}

	public void updateSourcingForVendor(String vendor, String vendorOrderNo) {
		this.sourcingData = SourcingData.builder()
			.sourcingVendor(vendor)
			.sourcingOrderNo(vendorOrderNo)
			.build();
	}

	public void markAsPurchased() {
		ensureShippingData();
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(ShippingStatus.PURCHASED)
			.build();
	}

	public void updateTrackingInfo(String trackingNo, ShippingCarrier carrier) {
		ensureShippingData();
		ShippingData.ShippingDataBuilder builder = this.shippingData.toBuilder();
		if (trackingNo != null)
			builder.trackingNo(trackingNo);
		if (carrier != null)
			builder.shippingCarrier(carrier);
		this.shippingData = builder.build();
	}

	public void updateShippingStatus(ShippingStatus status) {
		ensureShippingData();
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(status)
			.build();
	}

	private void ensureShippingData() {
		if (this.shippingData == null) {
			this.shippingData = ShippingData.builder().build();
		}
	}
}

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

	/** 상품 ID (Product 테이블 참조값, SB상품 매핑 시 사용) */
	@Column(name = "product_id")
	private Long productId;

	/** 주문 수량 */
	@Column(name = "quantity", nullable = false)
	private Integer quantity;

	@Column(name = "market_product_name", length = 255)
	private String marketProductName;

	/** 판매자 상품 코드 (SB코드 매핑용, 쿠팡의 externalVendorSkuCode 등) */
	@Column(name = "market_product_code")
	private String marketProductCode;

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
	public OrderLineItem(Long orderId, Long productId, Integer quantity, String marketProductName,
		String marketProductCode, SourcingData sourcingData,
		SettlementData settlementData, ShippingData shippingData) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = quantity;
		this.marketProductName = marketProductName;
		this.marketProductCode = marketProductCode;
		this.sourcingData = sourcingData != null ? sourcingData : SourcingData.builder().build();
		this.settlementData = settlementData != null ? settlementData : SettlementData.builder().build();
		this.shippingData = shippingData != null ? shippingData : ShippingData.builder().build();
	}

	protected void assignOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public void assignProductId(Long productId) {
		this.productId = productId;
	}

	public void updateMarketProductCode(String marketProductCode) {
		this.marketProductCode = marketProductCode;
	}

	public void updateSettlement(BigDecimal salePrice, BigDecimal settlementAmount, BigDecimal netProfit) {
		this.settlementData = SettlementData.builder()
			.salePrice(salePrice)
			.settlementAmount(settlementAmount)
			.netProfit(netProfit)
			.build();
	}

	public void updateShipping(
		String trackingNo,
		ShippingStatus status,
		Boolean isUnipassDone) {
		ShippingData.ShippingDataBuilder builder = (this.shippingData != null) ? this.shippingData.toBuilder()
			: ShippingData.builder();
		if (trackingNo != null)
			builder.trackingNo(trackingNo);
		if (status != null) {
			ShippingStatus current = this.shippingData != null ? this.shippingData.getShippingStatus() : null;
			if (current == null || !ShippingStatus.isDowngrade(current, status)) {
				builder.shippingStatus(status);
			}
		}
		if (isUnipassDone != null)
			builder.isUnipassDone(isUnipassDone);
		this.shippingData = builder.build();
	}

	public void updateShippingWithCarrier(
		String trackingNo,
		ShippingStatus status,
		Boolean isUnipassDone,
		com.sbshop.agent.core.domain.order.enums.ShippingCarrier carrier) {
		ShippingData.ShippingDataBuilder builder = (this.shippingData != null) ? this.shippingData.toBuilder()
			: ShippingData.builder();
		if (trackingNo != null)
			builder.trackingNo(trackingNo);
		// 상태 다운그레이드 방지: 현재 상태가 더 높으면 상태 변경 스킵
		if (status != null) {
			ShippingStatus current = this.shippingData != null ? this.shippingData.getShippingStatus() : null;
			if (current == null || !ShippingStatus.isDowngrade(current, status)) {
				builder.shippingStatus(status);
			}
		}
		if (isUnipassDone != null)
			builder.isUnipassDone(isUnipassDone);
		if (carrier != null)
			builder.shippingCarrier(carrier);
		this.shippingData = builder.build();
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

	// 아이허브 구매 처리
	public void updateSourcingForIherb(String account, String orderNo, String discountCode) {
		this.sourcingData = SourcingData.builder()
			.sourcingVendor("IHB")
			.sourcingAccount(account)
			.sourcingOrderNo(orderNo)
			.discountCode(discountCode)
			.build();
	}

	// 비아이허브 구매 처리
	public void updateSourcingForVendor(String vendor, String vendorOrderNo) {
		this.sourcingData = SourcingData.builder()
			.sourcingVendor(vendor)
			.sourcingOrderNo(vendorOrderNo)
			.build();
	}

	// PURCHASED 상태로 변경
	public void markAsPurchased() {
		ensureShippingData();
		this.shippingData = this.shippingData.toBuilder()
			.shippingStatus(ShippingStatus.PURCHASED)
			.build();
	}

	// 송장 업데이트 (배송처리/송장수정)
	public void updateTrackingInfo(String trackingNo,
		com.sbshop.agent.core.domain.order.enums.ShippingCarrier carrier) {
		ensureShippingData();
		ShippingData.ShippingDataBuilder builder = this.shippingData.toBuilder();
		if (trackingNo != null)
			builder.trackingNo(trackingNo);
		if (carrier != null)
			builder.shippingCarrier(carrier);
		this.shippingData = builder.build();
	}

	// 상태 변경
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

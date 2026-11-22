package com.sbshop.agent.core.domain.pricing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_vendor_price_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VendorPricePolicy extends BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "vendor", nullable = false, unique = true, length = 8)
	private VendorType vendor;

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	@Column(name = "coupon_rate", precision = 5, scale = 2)
	private BigDecimal couponRate;

	@Column(name = "min_margin_price", precision = 15, scale = 2)
	private BigDecimal minMarginPrice;

	@Column(name = "ship_currency", length = 3)
	private String shipCurrency;

	@Column(name = "ship_base_amount", precision = 10, scale = 2)
	private BigDecimal shipBaseAmount;

	@Column(name = "ship_base_weight_g")
	private Integer shipBaseWeightG;

	@Column(name = "ship_step_amount", precision = 10, scale = 2)
	private BigDecimal shipStepAmount;

	@Column(name = "ship_step_weight_g")
	private Integer shipStepWeightG;

	@Column(name = "domestic_fee", precision = 10, scale = 2)
	private BigDecimal domesticFee;

	@Column(name = "domestic_free_over", precision = 15, scale = 2)
	private BigDecimal domesticFreeOver;

	// 배대지 요금표(원화, D-360). 기본 금액이 비어 있거나 0이면 배대지 배송비를 더하지 않는다.
	@Column(name = "forwarder_base_amount", precision = 10, scale = 2)
	private BigDecimal forwarderBaseAmount;

	@Column(name = "forwarder_base_weight_g")
	private Integer forwarderBaseWeightG;

	@Column(name = "forwarder_step_amount", precision = 10, scale = 2)
	private BigDecimal forwarderStepAmount;

	@Column(name = "forwarder_step_weight_g")
	private Integer forwarderStepWeightG;

	@Column(name = "forwarder_packaging_g")
	private Integer forwarderPackagingG;

	@Builder
	public VendorPricePolicy(VendorType vendor, BigDecimal marginRate, BigDecimal couponRate,
		BigDecimal minMarginPrice, String shipCurrency, BigDecimal shipBaseAmount,
		Integer shipBaseWeightG, BigDecimal shipStepAmount, Integer shipStepWeightG,
		BigDecimal domesticFee, BigDecimal domesticFreeOver, ForwarderShipping.Rate forwarder) {
		this.vendor = vendor;
		this.marginRate = marginRate;
		this.couponRate = couponRate;
		this.minMarginPrice = minMarginPrice;
		this.shipCurrency = shipCurrency;
		this.shipBaseAmount = shipBaseAmount;
		this.shipBaseWeightG = shipBaseWeightG;
		this.shipStepAmount = shipStepAmount;
		this.shipStepWeightG = shipStepWeightG;
		this.domesticFee = domesticFee;
		this.domesticFreeOver = domesticFreeOver;
		applyForwarder(forwarder);
	}

	public void update(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice,
		String shipCurrency, BigDecimal shipBaseAmount, Integer shipBaseWeightG,
		BigDecimal shipStepAmount, Integer shipStepWeightG,
		BigDecimal domesticFee, BigDecimal domesticFreeOver, ForwarderShipping.Rate forwarder) {
		this.marginRate = marginRate;
		this.couponRate = couponRate;
		this.minMarginPrice = minMarginPrice;
		this.shipCurrency = shipCurrency;
		this.shipBaseAmount = shipBaseAmount;
		this.shipBaseWeightG = shipBaseWeightG;
		this.shipStepAmount = shipStepAmount;
		this.shipStepWeightG = shipStepWeightG;
		this.domesticFee = domesticFee;
		this.domesticFreeOver = domesticFreeOver;
		applyForwarder(forwarder);
	}

	public ForwarderShipping.Rate forwarderRate() {
		return new ForwarderShipping.Rate(forwarderBaseAmount, forwarderBaseWeightG, forwarderStepAmount,
			forwarderStepWeightG, forwarderPackagingG);
	}

	private void applyForwarder(ForwarderShipping.Rate forwarder) {
		this.forwarderBaseAmount = forwarder == null ? null : forwarder.baseAmount();
		this.forwarderBaseWeightG = forwarder == null ? null : forwarder.baseWeightG();
		this.forwarderStepAmount = forwarder == null ? null : forwarder.stepAmount();
		this.forwarderStepWeightG = forwarder == null ? null : forwarder.stepWeightG();
		this.forwarderPackagingG = forwarder == null ? null : forwarder.packagingG();
	}
}

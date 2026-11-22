package com.sbshop.agent.core.domain.fee;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.product.service.MarginCalculator.MarginFloor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_price_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PricePolicy extends BaseEntity {

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	@Column(name = "coupon_rate", precision = 5, scale = 2)
	private BigDecimal couponRate;

	@Column(name = "min_margin_price", precision = 15, scale = 2)
	private BigDecimal minMarginPrice;

	/** 마진 하한율(%) — 수수료를 뺀 순이익 ≥ 판매가 × 하한율. null이면 쓰지 않는다(D-360). */
	@Column(name = "margin_floor_rate", precision = 5, scale = 2)
	private BigDecimal marginFloorRate;

	/** 마진 하한액(원) — 수수료를 뺀 순이익 ≥ 하한액. null이면 쓰지 않는다(D-360). */
	@Column(name = "margin_floor_amount", precision = 15, scale = 2)
	private BigDecimal marginFloorAmount;

	@Builder
	public PricePolicy(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice,
		BigDecimal marginFloorRate, BigDecimal marginFloorAmount) {
		this.marginRate = marginRate;
		this.couponRate = couponRate;
		this.minMarginPrice = minMarginPrice;
		this.marginFloorRate = marginFloorRate;
		this.marginFloorAmount = marginFloorAmount;
	}

	public void update(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, MarginFloor floor) {
		this.marginRate = marginRate;
		this.couponRate = couponRate;
		this.minMarginPrice = minMarginPrice;
		this.marginFloorRate = floor == null ? null : floor.ratePercent();
		this.marginFloorAmount = floor == null ? null : floor.amount();
	}

	public MarginFloor marginFloor() {
		return new MarginFloor(marginFloorRate, marginFloorAmount);
	}
}

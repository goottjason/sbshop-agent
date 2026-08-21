package com.sbshop.agent.core.domain.fee;

import com.sbshop.agent.core.domain.common.BaseEntity;
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

	@Builder
	public PricePolicy(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		this.marginRate = marginRate;
		this.couponRate = couponRate;
		this.minMarginPrice = minMarginPrice;
	}

	public void update(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		this.marginRate = marginRate;
		this.couponRate = couponRate;
		this.minMarginPrice = minMarginPrice;
	}
}

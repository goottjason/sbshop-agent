package com.sbshop.agent.core.domain.order.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementData {
	@Column(name = "settlement_amount", precision = 10, scale = 2)
	private BigDecimal settlementAmount;

	@Column(name = "shipping_fee", precision = 10, scale = 2)
	private BigDecimal shippingFee;

	@Column(name = "settlement_verified")
	private Boolean settlementVerified;

	@Builder(toBuilder = true)
	public SettlementData(BigDecimal settlementAmount, BigDecimal shippingFee, Boolean settlementVerified) {
		this.settlementAmount = settlementAmount;
		this.shippingFee = shippingFee;
		this.settlementVerified = settlementVerified;
	}
}

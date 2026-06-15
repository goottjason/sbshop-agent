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
	@Column(name = "sale_price", precision = 10, scale = 2)
	private BigDecimal salePrice;

	@Column(name = "settlement_amount", precision = 10, scale = 2)
	private BigDecimal settlementAmount;

	@Column(name = "shipping_fee", precision = 10, scale = 2)
	private BigDecimal shippingFee;

	@Column(name = "net_profit", precision = 10, scale = 2)
	private BigDecimal netProfit;

	@Builder(toBuilder = true)
	public SettlementData(BigDecimal salePrice, BigDecimal settlementAmount, BigDecimal shippingFee,
		BigDecimal netProfit) {
		this.salePrice = salePrice;
		this.settlementAmount = settlementAmount;
		this.shippingFee = shippingFee;
		this.netProfit = netProfit;
	}
}

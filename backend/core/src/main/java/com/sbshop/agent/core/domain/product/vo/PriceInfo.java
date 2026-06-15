package com.sbshop.agent.core.domain.product.vo;

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
public class PriceInfo {
	@Column(name = "cost_price", precision = 10, scale = 2)
	private BigDecimal costPrice;

	@Column(name = "exchange_rate", precision = 10, scale = 4)
	private BigDecimal exchangeRate;

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	@Column(name = "sale_price", precision = 10, scale = 2)
	private BigDecimal salePrice;

	@Builder
	public PriceInfo(
		BigDecimal costPrice, BigDecimal exchangeRate, BigDecimal marginRate, BigDecimal salePrice) {
		this.costPrice = costPrice;
		this.exchangeRate = exchangeRate;
		this.marginRate = marginRate;
		this.salePrice = salePrice;
	}
}

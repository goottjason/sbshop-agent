package com.sbshop.agent.core.domain.product.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PriceInfo {

	@Column(name = "cost_price", precision = 15, scale = 2)
	private BigDecimal costPrice;

	@Column(name = "exchange_rate", precision = 10, scale = 2)
	private BigDecimal exchangeRate;

	@Column(name = "delivery_fee", precision = 15, scale = 2)
	private BigDecimal deliveryFee;

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	@Column(name = "sale_price", precision = 15, scale = 0)
	private BigDecimal salePrice;
}

package com.sbshop.agent.core.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class MarginCalculator {

	private static final BigDecimal CHANNEL_FEE_RATE = new BigDecimal("18.5");
	private static final BigDecimal DELIVERY_FEE_THRESHOLD = new BigDecimal("40000");
	private static final BigDecimal DELIVERY_FEE = new BigDecimal("6000");

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
			BigDecimal marginRate, BigDecimal minMarginPrice) {
		BigDecimal totalBuyPrice = buyPrice.multiply(BigDecimal.valueOf(bundleQty));
		if (totalBuyPrice.compareTo(DELIVERY_FEE_THRESHOLD) < 0) {
			totalBuyPrice = totalBuyPrice.add(DELIVERY_FEE);
		}

		BigDecimal divisor = BigDecimal.ONE
				.subtract(marginRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
				.subtract(CHANNEL_FEE_RATE.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));

		BigDecimal salePrice = totalBuyPrice.divide(divisor, 0, RoundingMode.CEILING);
		salePrice = new BigDecimal(salePrice.intValue() +
				(100 - salePrice.intValue() % 100));

		if (minMarginPrice != null) {
			BigDecimal margin = salePrice.subtract(totalBuyPrice);
			if (margin.compareTo(minMarginPrice) < 0) {
				salePrice = salePrice.add(minMarginPrice.subtract(margin));
			}
		}

		return salePrice;
	}

	public BigDecimal getEffectiveBuyPrice(BigDecimal listPrice, BigDecimal discountPrice,
			int discountType, int couponRate, int salesDiscount) {
		if (discountType == 2 && discountPrice != null && discountPrice.compareTo(BigDecimal.ZERO) > 0) {
			return discountPrice;
		}
		int appliedRate = Math.max(couponRate, salesDiscount);
		return listPrice.multiply(BigDecimal.valueOf(100 - appliedRate))
				.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
	}
}

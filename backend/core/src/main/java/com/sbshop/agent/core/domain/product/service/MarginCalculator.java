package com.sbshop.agent.core.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class MarginCalculator {

	private static final BigDecimal CHANNEL_FEE_RATE = new BigDecimal("18.5");
	private static final BigDecimal DELIVERY_FEE_THRESHOLD = new BigDecimal("40000");
	private static final BigDecimal DELIVERY_FEE = new BigDecimal("6000");

	/**
	 * 쿠폰 할인을 반영한 판매가 계산(F-BATCH-6).
	 *
	 * <p>{@code couponRate}(%)만큼 구매가를 낮춘 <b>실매입가</b>로 판매가를 산정한다. 예) 구매가 10000·쿠폰 20%
	 * → 실매입가 8000으로 계산 → 그만큼 더 저렴하게 판매 가능. couponRate가 null·0 이하면 할인을 적용하지
	 * 않아 {@link #calculateSalePrice(BigDecimal, int, BigDecimal, BigDecimal)}와 동일하다.
	 */
	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		return calculateSalePrice(applyCoupon(buyPrice, couponRate), bundleQty, marginRate, minMarginPrice);
	}

	private BigDecimal applyCoupon(BigDecimal buyPrice, BigDecimal couponRate) {
		if (couponRate == null || couponRate.signum() <= 0) {
			return buyPrice;
		}
		BigDecimal factor = BigDecimal.ONE.subtract(
			couponRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
		return buyPrice.multiply(factor);
	}

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

package com.sbshop.agent.core.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class MarginCalculator {
	private static final BigDecimal DEFAULT_CHANNEL_FEE_RATE = new BigDecimal("18.5");
	private static final BigDecimal DELIVERY_FEE_THRESHOLD = new BigDecimal("40000");
	private static final BigDecimal DELIVERY_FEE = new BigDecimal("6000");

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		return calculateSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice,
			DEFAULT_CHANNEL_FEE_RATE);
	}

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate) {
		return computeSalePrice(applyCoupon(buyPrice, couponRate), bundleQty, marginRate, minMarginPrice,
			channelFeeRate, DELIVERY_FEE, DELIVERY_FEE_THRESHOLD);
	}

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
		return computeSalePrice(applyCoupon(buyPrice, couponRate), bundleQty, marginRate, minMarginPrice,
			channelFeeRate, domesticFee, domesticFreeOver);
	}

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal minMarginPrice) {
		return computeSalePrice(buyPrice, bundleQty, marginRate, minMarginPrice, DEFAULT_CHANNEL_FEE_RATE,
			DELIVERY_FEE, DELIVERY_FEE_THRESHOLD);
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

	/**
	 * 국내 배송비는 소싱처마다 다르다 — 아이허브는 4만원 미만 매입 시 6,000원이 붙고,
	 * 배대지를 쓰는 영국 소싱처는 붙지 않는다(사용자 확인 2026-08-30). 정책값이 0 이면 가산하지 않는다.
	 */
	private BigDecimal domesticFee(BigDecimal totalBuyPrice, BigDecimal fee, BigDecimal freeOver) {
		if (fee == null || fee.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		if (freeOver != null && freeOver.signum() > 0 && totalBuyPrice.compareTo(freeOver) >= 0) {
			return BigDecimal.ZERO;
		}
		return fee;
	}

	private BigDecimal applyCoupon(BigDecimal buyPrice, BigDecimal couponRate) {
		if (couponRate == null || couponRate.signum() <= 0) {
			return buyPrice;
		}
		BigDecimal factor = BigDecimal.ONE.subtract(
			couponRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
		return buyPrice.multiply(factor);
	}

	private BigDecimal computeSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
		BigDecimal totalBuyPrice = buyPrice.multiply(BigDecimal.valueOf(bundleQty));
		totalBuyPrice = totalBuyPrice.add(domesticFee(totalBuyPrice, domesticFee, domesticFreeOver));

		BigDecimal divisor = BigDecimal.ONE
			.subtract(marginRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
			.subtract(channelFeeRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));

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
}

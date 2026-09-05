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
		return quoteSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice, channelFeeRate).salePrice();
	}

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
		return quoteSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice, channelFeeRate,
			domesticFee, domesticFreeOver).salePrice();
	}

	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal minMarginPrice) {
		return computeSalePrice(buyPrice, bundleQty, marginRate, minMarginPrice, DEFAULT_CHANNEL_FEE_RATE,
			DELIVERY_FEE, DELIVERY_FEE_THRESHOLD).salePrice();
	}

	public SalePriceRounding.Result quoteSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate) {
		return quoteSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice, channelFeeRate,
			DELIVERY_FEE, DELIVERY_FEE_THRESHOLD);
	}

	public SalePriceRounding.Result quoteSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
		return computeSalePrice(applyCoupon(buyPrice, couponRate), bundleQty, marginRate, minMarginPrice,
			channelFeeRate, domesticFee, domesticFreeOver);
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
		if (buyPrice == null)
			throw new IllegalArgumentException("가격 계산에 매입가가 필요합니다.");
		if (couponRate == null || couponRate.signum() <= 0) {
			return buyPrice;
		}
		BigDecimal factor = BigDecimal.ONE.subtract(
			couponRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
		return buyPrice.multiply(factor);
	}

	private SalePriceRounding.Result computeSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
		if (buyPrice == null || buyPrice.signum() < 0 || bundleQty < 1 || marginRate == null
			|| channelFeeRate == null) {
			throw new IllegalArgumentException("가격 계산에 유효한 매입가·묶음수량·마진율·채널수수료가 필요합니다.");
		}
		if (minMarginPrice != null && minMarginPrice.signum() < 0) {
			throw new IllegalArgumentException("최소마진은 0 이상이어야 합니다.");
		}
		BigDecimal totalBuyPrice = buyPrice.multiply(BigDecimal.valueOf(bundleQty));
		totalBuyPrice = totalBuyPrice.add(domesticFee(totalBuyPrice, domesticFee, domesticFreeOver));

		BigDecimal divisor = BigDecimal.ONE
			.subtract(marginRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
			.subtract(channelFeeRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));

		// 기존 최소마진 정의(판매가 - 쿠폰 적용 총 매입가 - 국내 배송비)를 유지한다.
		BigDecimal minimumPrice = minMarginPrice == null ? null : totalBuyPrice.add(minMarginPrice);
		var result = SalePriceRounding.fromRatio(totalBuyPrice, divisor, minimumPrice);
		if (result.salePrice().signum() <= 0) {
			throw new IllegalArgumentException("100원 단위 처리 후 판매가가 0원입니다. 매입가와 최소마진을 확인하세요.");
		}
		return result;
	}
}

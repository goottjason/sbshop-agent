package com.sbshop.agent.core.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class MarginCalculator {

	/**
	 * 기본 채널수수료(%). 마켓 컨텍스트가 없는 하위호환 시그니처에서만 쓰인다.
	 * 마켓별 실수수료는 {@code sb_fee_policy}(쿠팡 11·스토어 8·G마켓/11번가 18 등)에서 조회해
	 * 수수료 파라미터가 있는 오버로드로 전달한다(D-094).
	 */
	private static final BigDecimal DEFAULT_CHANNEL_FEE_RATE = new BigDecimal("18.5");
	private static final BigDecimal DELIVERY_FEE_THRESHOLD = new BigDecimal("40000");
	private static final BigDecimal DELIVERY_FEE = new BigDecimal("6000");

	/**
	 * 쿠폰 할인을 반영한 판매가 계산(F-BATCH-6).
	 *
	 * <p>{@code couponRate}(%)만큼 구매가를 낮춘 <b>실매입가</b>로 판매가를 산정한다. 예) 구매가 10000·쿠폰 20%
	 * → 실매입가 8000으로 계산 → 그만큼 더 저렴하게 판매 가능. couponRate가 null·0 이하면 할인을 적용하지
	 * 않아 {@link #calculateSalePrice(BigDecimal, int, BigDecimal, BigDecimal)}와 동일하다.
	 *
	 * <p>채널수수료는 기본값 18.5%. 마켓별 실수수료로 산정하려면
	 * {@link #calculateSalePrice(BigDecimal, int, BigDecimal, BigDecimal, BigDecimal, BigDecimal)}를 쓴다.
	 */
	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
		return calculateSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice,
			DEFAULT_CHANNEL_FEE_RATE);
	}

	/**
	 * D-094: 채널수수료(%)를 명시해 마켓별 실수수료로 판매가를 산정한다.
	 * 쿠폰 반영(실매입가) → 마진/수수료 divisor → 100원 올림 → 최소마진 보정 순서는 동일하다.
	 */
	public BigDecimal calculateSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate) {
		return computeSalePrice(applyCoupon(buyPrice, couponRate), bundleQty, marginRate, minMarginPrice,
			channelFeeRate);
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
		return computeSalePrice(buyPrice, bundleQty, marginRate, minMarginPrice, DEFAULT_CHANNEL_FEE_RATE);
	}

	/** 쿠폰 반영 후 실매입가(totalUnitBuyPrice)로 판매가를 산정하는 핵심 계산. channelFeeRate는 %(예: 11). */
	private BigDecimal computeSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate) {
		BigDecimal totalBuyPrice = buyPrice.multiply(BigDecimal.valueOf(bundleQty));
		if (totalBuyPrice.compareTo(DELIVERY_FEE_THRESHOLD) < 0) {
			totalBuyPrice = totalBuyPrice.add(DELIVERY_FEE);
		}

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

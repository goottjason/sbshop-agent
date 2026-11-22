package com.sbshop.agent.core.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class MarginCalculator {
	private static final BigDecimal DEFAULT_CHANNEL_FEE_RATE = new BigDecimal("18.5");
	public static final BigDecimal DELIVERY_FEE_THRESHOLD = new BigDecimal("40000");
	public static final BigDecimal DELIVERY_FEE = new BigDecimal("6000");

	/**
	 * 마진 하한(D-360 6-3 고정 결정 6·7). 수수료를 뺀 순이익이 min(판매가 × 하한율, 하한액) 아래로 내려가지 않게 한다.
	 * 둘 중 하나만 있으면 그 하나만, 둘 다 없으면 적용하지 않는다.
	 */
	public record MarginFloor(BigDecimal ratePercent, BigDecimal amount) {
		public static final MarginFloor NONE = new MarginFloor(null, null);
	}

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
			DELIVERY_FEE, DELIVERY_FEE_THRESHOLD, BigDecimal.ZERO, MarginFloor.NONE).salePrice();
	}

	public SalePriceRounding.Result quoteSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate) {
		return quoteSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice, channelFeeRate,
			DELIVERY_FEE, DELIVERY_FEE_THRESHOLD);
	}

	public SalePriceRounding.Result quoteSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
		return quoteSalePrice(buyPrice, bundleQty, marginRate, couponRate, minMarginPrice, channelFeeRate,
			domesticFee, domesticFreeOver, BigDecimal.ZERO, MarginFloor.NONE);
	}

	/**
	 * 배대지 배송비는 쿠폰·국내 배송비 무료 기준을 적용한 뒤 총 매입가에 더한다(D-360 6-3 고정 결정 2).
	 */
	public SalePriceRounding.Result quoteSalePrice(BigDecimal buyPrice, int bundleQty,
		BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice, BigDecimal channelFeeRate,
		BigDecimal domesticFee, BigDecimal domesticFreeOver, BigDecimal forwarderFee, MarginFloor floor) {
		return computeSalePrice(applyCoupon(buyPrice, couponRate), bundleQty, marginRate, minMarginPrice,
			channelFeeRate, domesticFee, domesticFreeOver, forwarderFee, floor);
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
		BigDecimal domesticFee, BigDecimal domesticFreeOver, BigDecimal forwarderFee, MarginFloor floor) {
		if (buyPrice == null || buyPrice.signum() < 0 || bundleQty < 1 || marginRate == null
			|| channelFeeRate == null) {
			throw new IllegalArgumentException("가격 계산에 유효한 매입가·묶음수량·마진율·채널수수료가 필요합니다.");
		}
		if (minMarginPrice != null && minMarginPrice.signum() < 0) {
			throw new IllegalArgumentException("최소마진은 0 이상이어야 합니다.");
		}
		if (forwarderFee != null && forwarderFee.signum() < 0) {
			throw new IllegalArgumentException("배대지 배송비는 0 이상이어야 합니다.");
		}
		BigDecimal totalBuyPrice = buyPrice.multiply(BigDecimal.valueOf(bundleQty));
		totalBuyPrice = totalBuyPrice.add(domesticFee(totalBuyPrice, domesticFee, domesticFreeOver));
		// 쿠폰과 국내 배송비 무료 기준은 배대지 배송비를 더하기 전 금액에 적용한다(D-360 6-3 고정 결정 2).
		if (forwarderFee != null) {
			totalBuyPrice = totalBuyPrice.add(forwarderFee);
		}

		BigDecimal divisor = BigDecimal.ONE
			.subtract(marginRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
			.subtract(channelFeeRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));

		// 기존 최소마진 정의(판매가 - 쿠폰 적용 총 매입가 - 국내 배송비)를 유지한다.
		BigDecimal minimumPrice = minMarginPrice == null ? null : totalBuyPrice.add(minMarginPrice);
		BigDecimal feeKept = BigDecimal.ONE.subtract(channelFeeRate.divide(BigDecimal.valueOf(100), 4,
			RoundingMode.HALF_UP));
		if (channelFeeRate.signum() > 0 && feeKept.signum() > 0) {
			BigDecimal breakEven = totalBuyPrice.divide(feeKept, 0, RoundingMode.CEILING);
			minimumPrice = minimumPrice == null ? breakEven : minimumPrice.max(breakEven);
		}
		var result = SalePriceRounding.fromRatio(totalBuyPrice, divisor, minimumPrice);
		BigDecimal floorPrice = floorPrice(totalBuyPrice, channelFeeRate, floor);
		if (floorPrice != null) {
			// 판매가 = max(목표가, 기존 최저가, 하한가). 하한가도 최저가에 넣어 검토 화면의 최저가와 맞춘다.
			var floored = SalePriceRounding.fromRatio(totalBuyPrice, divisor,
				minimumPrice == null ? floorPrice : minimumPrice.max(floorPrice));
			result = floored.salePrice().compareTo(result.salePrice()) > 0
				? new SalePriceRounding.Result(floored.roundedPrice(), floored.minimumPrice(), floored.salePrice(),
					result.salePrice())
				: floored;
		}
		if (result.salePrice().signum() <= 0) {
			throw new IllegalArgumentException("100원 단위 처리 후 판매가가 0원입니다. 매입가와 최소마진을 확인하세요.");
		}
		return result;
	}

	/**
	 * 하한가 = min(총 매입가 ÷ (1 − 수수료율 − 하한율), (총 매입가 + 하한액) ÷ (1 − 수수료율)), 원 단위 올림.
	 * 하한이 없으면 null.
	 */
	private static BigDecimal floorPrice(BigDecimal totalBuyPrice, BigDecimal channelFeeRate, MarginFloor floor) {
		if (floor == null || floor.ratePercent() == null && floor.amount() == null) {
			return null;
		}
		if (floor.ratePercent() != null && floor.ratePercent().signum() < 0
			|| floor.amount() != null && floor.amount().signum() < 0) {
			throw new IllegalArgumentException("마진 하한율·하한액은 0 이상이어야 합니다.");
		}
		BigDecimal feeKept = BigDecimal.ONE.subtract(channelFeeRate.divide(BigDecimal.valueOf(100), 4,
			RoundingMode.HALF_UP));
		BigDecimal byRate = null;
		if (floor.ratePercent() != null) {
			BigDecimal kept = feeKept.subtract(floor.ratePercent().divide(BigDecimal.valueOf(100), 4,
				RoundingMode.HALF_UP));
			if (kept.signum() <= 0) {
				throw new IllegalArgumentException("마진 하한율과 채널수수료의 합은 100% 미만이어야 합니다.");
			}
			byRate = totalBuyPrice.divide(kept, 0, RoundingMode.CEILING);
		}
		BigDecimal byAmount = null;
		if (floor.amount() != null) {
			if (feeKept.signum() <= 0) {
				throw new IllegalArgumentException("채널수수료는 100% 미만이어야 합니다.");
			}
			byAmount = totalBuyPrice.add(floor.amount()).divide(feeKept, 0, RoundingMode.CEILING);
		}
		return byRate == null ? byAmount : byAmount == null ? byRate : byRate.min(byAmount);
	}
}

package com.sbshop.agent.core.domain.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class LandedCostCalculator {

	private LandedCostCalculator() {
	}

	public static BigDecimal buyPricePerUnit(BigDecimal goodsKrw, BigDecimal weightKg, int bundleQty,
		VendorPricePolicy policy, BigDecimal fxRate) {
		if (goodsKrw == null || goodsKrw.signum() <= 0) {
			throw new IllegalStateException("매입 원가가 없다 — 판매가를 만들 수 없다");
		}
		if (policy == null) {
			throw new IllegalStateException("소싱처 가격 정책이 없다 — 배송비를 0 으로 두면 원가를 과소평가한다."
				+ " 설정 및 연동 > 가격 정책에서 등록할 것");
		}
		BigDecimal shipCurrency = shippingInCurrency(weightKg, policy);
		if (shipCurrency.signum() == 0) {
			return goodsKrw;
		}
		if (fxRate == null || fxRate.signum() <= 0) {
			throw new IllegalStateException("환율이 없다 — 배송비가 0 이 되어버린다");
		}
		BigDecimal shippingKrw = shipCurrency.multiply(fxRate).setScale(0, RoundingMode.HALF_UP);
		int qty = Math.max(bundleQty, 1);
		return goodsKrw.add(shippingKrw.divide(BigDecimal.valueOf(qty), 0, RoundingMode.HALF_UP));
	}

	private static BigDecimal shippingInCurrency(BigDecimal weightKg, VendorPricePolicy policy) {
		BigDecimal base = policy.getShipBaseAmount();
		if (base == null || base.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		if (weightKg == null || weightKg.signum() <= 0) {
			throw new IllegalStateException("상품 무게가 없어 배송비를 매길 수 없다 —"
				+ " 기초 배송비로 때우면 무거운 상품이 조용히 저가로 등록된다");
		}
		double grams = weightKg.multiply(BigDecimal.valueOf(1000)).doubleValue();
		BigDecimal amount = VendorShippingCalculator.amount(grams, policy);
		if (amount == null) {
			throw new IllegalStateException("배송비 정책이 불완전하다: " + policy.getVendor());
		}
		return amount;
	}
}

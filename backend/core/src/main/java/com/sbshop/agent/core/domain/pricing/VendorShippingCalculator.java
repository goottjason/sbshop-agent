package com.sbshop.agent.core.domain.pricing;

import java.math.BigDecimal;

public final class VendorShippingCalculator {

	private VendorShippingCalculator() {
	}

	public static BigDecimal amount(Double weightGrams, VendorPricePolicy policy) {
		if (policy == null || policy.getShipBaseAmount() == null) {
			return null;
		}
		BigDecimal base = policy.getShipBaseAmount();
		if (base.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		Integer baseWeight = policy.getShipBaseWeightG();
		Integer stepWeight = policy.getShipStepWeightG();
		BigDecimal stepAmount = policy.getShipStepAmount();
		if (weightGrams == null || weightGrams <= 0 || baseWeight == null || baseWeight <= 0
			|| stepWeight == null || stepWeight <= 0 || stepAmount == null || stepAmount.signum() <= 0) {
			return base;
		}
		double over = weightGrams - baseWeight;
		if (over <= 0) {
			return base;
		}
		long steps = (long)Math.ceil(over / stepWeight);
		return base.add(stepAmount.multiply(BigDecimal.valueOf(steps)));
	}
}

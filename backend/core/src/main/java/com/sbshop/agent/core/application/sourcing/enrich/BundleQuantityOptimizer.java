package com.sbshop.agent.core.application.sourcing.enrich;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class BundleQuantityOptimizer {
	private static final BigDecimal DEFAULT_SHIPPING_FEE = new BigDecimal("6000");

	private static final BigDecimal MARGINAL_GAIN_THRESHOLD = new BigDecimal("0.02");

	private static final int MAX_BUNDLE = 6;

	private static final int SUPPLEMENT_SAFE_MAX = 6;

	private static final double MAX_TOTAL_WEIGHT_G = 2000.0;

	public Recommendation recommend(BigDecimal unitCost, Double unitWeightG) {
		if (unitCost == null || unitCost.signum() <= 0)
			return new Recommendation(1, "매입가 불명 — 단품으로 등록");

		int weightCap = weightCap(unitWeightG);
		int cap = Math.min(Math.min(MAX_BUNDLE, SUPPLEMENT_SAFE_MAX), weightCap);
		if (cap <= 1) {
			return new Recommendation(1, weightCap <= 1
				? "단품 무게가 커 묶음 배송이 불리함" : "묶음 상한 1");
		}

		int best = 1;
		BigDecimal bestPerUnit = perUnitCost(unitCost, 1);
		for (int qty = 2; qty <= cap; qty++) {
			BigDecimal perUnit = perUnitCost(unitCost, qty);
			BigDecimal gain = bestPerUnit.subtract(perUnit)
				.divide(bestPerUnit, 4, RoundingMode.HALF_UP);
			if (gain.compareTo(MARGINAL_GAIN_THRESHOLD) < 0)
				break;
			best = qty;
			bestPerUnit = perUnit;
		}

		BigDecimal singlePerUnit = perUnitCost(unitCost, 1);
		BigDecimal saved = singlePerUnit.subtract(bestPerUnit);
		String reason = best == 1
			? "묶음으로 얻는 개당 원가 절감이 크지 않음"
			: "%d개 묶음 시 개당 원가 %s원 절감(배송비 %s원을 %d개로 분산)".formatted(
				best, saved.setScale(0, RoundingMode.DOWN),
				DEFAULT_SHIPPING_FEE.setScale(0, RoundingMode.DOWN), best);
		return new Recommendation(best, reason);
	}

	private BigDecimal perUnitCost(BigDecimal unitCost, int qty) {
		return unitCost.multiply(BigDecimal.valueOf(qty))
			.add(DEFAULT_SHIPPING_FEE)
			.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP);
	}

	private int weightCap(Double unitWeightG) {
		if (unitWeightG == null || unitWeightG <= 0)
			return MAX_BUNDLE;
		return (int)Math.max(1, Math.floor(MAX_TOTAL_WEIGHT_G / unitWeightG));
	}

	public record Recommendation(int quantity, String reason) {
	}
}

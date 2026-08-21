package com.sbshop.agent.core.application.sourcing.enrich;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 묶음 수량 추천 — 배대지 배송비를 개당으로 얼마나 희석할 수 있는가로 정한다.
 *
 * <p>해외직구는 <b>배송비가 주문당 1회</b> 붙는다. 단품 20,000원짜리에 배송비 6,000원이면
 * 실질 원가가 26,000원(+30%)이지만, 2개 묶음이면 46,000원(개당 23,000원, +15%)이 된다.
 * 묶음을 키울수록 개당 원가는 내려가지만 객단가가 올라가 판매량이 줄고 무게 상한에 걸린다.
 *
 * <p>그래서 "배송비 희석 효과가 충분히 큰 최소 수량"을 고른다 —
 * 수량을 하나 더 늘려도 개당 원가가 {@value #MARGINAL_GAIN_THRESHOLD} 미만으로밖에 안 줄면 거기서 멈춘다.
 */
@Component
public class BundleQuantityOptimizer {

	/** 해외 배대지 기본 배송비(원). {@code MarginCalculator}의 DELIVERY_FEE와 같은 값. */
	private static final BigDecimal DEFAULT_SHIPPING_FEE = new BigDecimal("6000");

	/** 이 이상 개당 원가가 줄지 않으면 수량을 더 늘리지 않는다(2%). */
	private static final BigDecimal MARGINAL_GAIN_THRESHOLD = new BigDecimal("0.02");

	/** 객단가·재고 부담을 고려한 상한. */
	private static final int MAX_BUNDLE = 6;

	/** 개인통관 자가사용 인정 기준(건강기능식품 총 6병)을 넘지 않도록 하는 안전 상한. */
	private static final int SUPPLEMENT_SAFE_MAX = 6;

	/** 배송 무게 상한(g). 넘으면 배송비 구간이 올라 이득이 사라진다. */
	private static final double MAX_TOTAL_WEIGHT_G = 2000.0;

	/**
	 * @param unitCost   단품 매입가(원)
	 * @param unitWeightG 단품 배송 무게(g). 모르면 null.
	 */
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

	/** 개당 실질 원가 = (단품가 × 수량 + 배송비) / 수량. */
	private BigDecimal perUnitCost(BigDecimal unitCost, int qty) {
		return unitCost.multiply(BigDecimal.valueOf(qty))
			.add(DEFAULT_SHIPPING_FEE)
			.divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP);
	}

	private int weightCap(Double unitWeightG) {
		if (unitWeightG == null || unitWeightG <= 0)
			return MAX_BUNDLE; // 무게 불명이면 제한하지 않는다(일반 상한만 적용)
		return (int)Math.max(1, Math.floor(MAX_TOTAL_WEIGHT_G / unitWeightG));
	}

	/** @param reason 검수 화면에 근거로 보여준다 — 사용자가 수량을 바꿀지 판단하는 재료. */
	public record Recommendation(int quantity, String reason) {
	}
}

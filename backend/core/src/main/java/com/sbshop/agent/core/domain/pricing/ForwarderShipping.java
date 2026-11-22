package com.sbshop.agent.core.domain.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * 배대지(배송대행지) 배송비 — D-360 6-3 고정 결정 3·5.
 * 배대지 배송비 = 기본 금액 + 단계 금액 × ⌈max(0, 계산 무게 − 기본 무게) ÷ 단계 무게⌉,
 * 계산 무게(g) = 상품 무게(kg) × 1000 × 묶음수량 + 포장 가산(g). 원화 고정, 환율을 곱하지 않는다.
 * 기존 해외 배송비({@code ship_*}, {@link LandedCostCalculator})와는 별개다.
 */
public final class ForwarderShipping {

	private static final BigDecimal GRAMS_PER_KG = BigDecimal.valueOf(1000);

	private ForwarderShipping() {
	}

	/** 소싱처 정책의 배대지 요금표. 기본 금액이 비어 있거나 0이면 배대지를 쓰지 않는 소싱처다. */
	public record Rate(BigDecimal baseAmount, Integer baseWeightG, BigDecimal stepAmount, Integer stepWeightG,
		Integer packagingG) {
		public boolean enabled() {
			return baseAmount != null && baseAmount.signum() > 0;
		}
	}

	/** 계산 불가이면 amount는 null이고 unavailableReason에 사유가 있다. 배대지 미사용이면 amount 0·note null. */
	public record Quote(BigDecimal amount, BigDecimal chargeableGrams, String note, String unavailableReason) {
		static final Quote NONE = new Quote(BigDecimal.ZERO, null, null, null);

		public boolean available() {
			return unavailableReason == null;
		}

		static Quote unavailable(String reason) {
			return new Quote(null, null, null, reason);
		}
	}

	public static Quote quote(Rate rate, BigDecimal weightKg, int bundleQty) {
		if (rate == null || !rate.enabled()) {
			return Quote.NONE;
		}
		if (rate.baseWeightG() == null || rate.baseWeightG() <= 0 || rate.stepWeightG() == null
			|| rate.stepWeightG() <= 0 || rate.stepAmount() == null || rate.stepAmount().signum() < 0
			|| rate.packagingG() != null && rate.packagingG() < 0) {
			return Quote.unavailable("배대지 요금표(기본 무게·단계 금액·단계 무게·포장 가산)가 비어 있거나 올바르지 않아 "
				+ "판매가를 계산하지 않았습니다. 소싱처 가격 정책을 확인하세요.");
		}
		if (weightKg == null || weightKg.signum() <= 0) {
			return Quote.unavailable("배대지 배송비를 계산하려면 상품 무게가 필요합니다. 무게가 없어 판매가를 계산하지 않았고 "
				+ "기존 가격을 유지합니다.");
		}
		int bundle = Math.max(1, bundleQty);
		int packaging = rate.packagingG() == null ? 0 : rate.packagingG();
		BigDecimal unitGrams = weightKg.multiply(GRAMS_PER_KG);
		BigDecimal grams = unitGrams.multiply(BigDecimal.valueOf(bundle)).add(BigDecimal.valueOf(packaging));
		BigDecimal over = grams.subtract(BigDecimal.valueOf(rate.baseWeightG()));
		BigDecimal steps = over.signum() <= 0 ? BigDecimal.ZERO
			: over.divide(BigDecimal.valueOf(rate.stepWeightG()), 0, RoundingMode.CEILING);
		BigDecimal amount = rate.baseAmount().add(rate.stepAmount().multiply(steps));
		String note = "배대지 배송비 " + won(amount) + "원 · 계산 무게 " + plain(grams) + "g = " + plain(unitGrams) + "g × "
			+ bundle + " + 포장 " + packaging + "g";
		return new Quote(amount, grams, note, null);
	}

	private static String won(BigDecimal value) {
		return new DecimalFormat("#,##0.##").format(value);
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}
}

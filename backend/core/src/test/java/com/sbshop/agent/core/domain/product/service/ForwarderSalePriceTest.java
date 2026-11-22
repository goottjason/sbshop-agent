package com.sbshop.agent.core.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.service.MarginCalculator.MarginFloor;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-360 6-3 고정 결정 1·2 — 배대지 배송비는 판매가 계산 때 총 매입가에 더한다(쿠폰·국내 배송비 기준 적용 뒤). */
class ForwarderSalePriceTest {
	private final MarginCalculator calculator = new MarginCalculator();
	private static final MarginFloor FLOOR_5_5000 = new MarginFloor(new BigDecimal("5"), new BigDecimal("5000"));

	@Test
	@DisplayName("예 A: 27,881원 × 2 + 배대지 13,100원 = 68,862원, 마진 15%·최소마진 15,000·수수료 11% → 93,100원")
	void exampleA() {
		var result = calculator.quoteSalePrice(new BigDecimal("27881"), 2, new BigDecimal("15"), BigDecimal.ZERO,
			new BigDecimal("15000"), new BigDecimal("11"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("13100"),
			FLOOR_5_5000);

		assertThat(result.roundedPrice()).isEqualByComparingTo("93100");
		assertThat(result.salePrice()).isEqualByComparingTo("93100");
		assertThat(result.floorApplied()).isFalse();
		// 기존 최저가 = 68,862 + 15,000 = 83,862원 (하한가 81,979원보다 높다)
		assertThat(result.minimumPrice()).isEqualByComparingTo("83862");
	}

	@Test
	@DisplayName("예 B: 예 A에서 마진 3%·최소마진 0 → 하한가 82,000원")
	void exampleB() {
		var result = calculator.quoteSalePrice(new BigDecimal("27881"), 2, new BigDecimal("3"), BigDecimal.ZERO,
			BigDecimal.ZERO, new BigDecimal("11"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("13100"),
			FLOOR_5_5000);

		assertThat(result.salePrice()).isEqualByComparingTo("82000");
		assertThat(result.floorRaisedFrom()).isEqualByComparingTo("80100");
	}

	@Test
	@DisplayName("예 D: 쿠폰 10%는 배대지 배송비에 먹지 않는다 — 총 매입가 28,500원(27,450원이 아니다)")
	void exampleD_couponDoesNotDiscountForwarderFee() {
		var result = calculator.quoteSalePrice(new BigDecimal("20000"), 1, BigDecimal.ZERO, new BigDecimal("10"),
			BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10500"),
			MarginFloor.NONE);

		assertThat(result.salePrice()).isEqualByComparingTo("28500");
	}

	@Test
	@DisplayName("국내 배송비 무료 기준은 배대지 배송비를 더하기 전 금액으로 본다")
	void domesticThresholdIgnoresForwarderFee() {
		var result = calculator.quoteSalePrice(new BigDecimal("35000"), 1, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("6000"), new BigDecimal("40000"), new BigDecimal("10500"),
			MarginFloor.NONE);

		assertThat(result.salePrice()).isEqualByComparingTo("51500");
	}

	@Test
	@DisplayName("배대지 배송비 0·하한 없음이면 지금 식과 1원도 다르지 않다")
	void zeroForwarderFeeKeepsCurrentResult() {
		var before = calculator.quoteSalePrice(new BigDecimal("27881"), 2, new BigDecimal("15"), new BigDecimal("20"),
			new BigDecimal("15000"), new BigDecimal("11"), new BigDecimal("6000"), new BigDecimal("40000"));
		var after = calculator.quoteSalePrice(new BigDecimal("27881"), 2, new BigDecimal("15"), new BigDecimal("20"),
			new BigDecimal("15000"), new BigDecimal("11"), new BigDecimal("6000"), new BigDecimal("40000"),
			BigDecimal.ZERO, MarginFloor.NONE);

		assertThat(after).isEqualTo(before);
	}
}

package com.sbshop.agent.core.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.product.service.MarginCalculator.MarginFloor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** D-360 6-3 고정 결정 6·8 — 수수료를 뺀 순이익이 min(판매가 × 하한율, 하한액) 아래로 내려가지 않는다. */
class MarginFloorTest {
	private final MarginCalculator calculator = new MarginCalculator();
	private static final MarginFloor FLOOR_5_5000 = new MarginFloor(new BigDecimal("5"), new BigDecimal("5000"));

	/** 국내 배송비 0, 쿠폰 0 — 총 매입가 = 개당 원가 × 묶음수량. */
	private SalePriceRounding.Result quote(String unitCost, int bundle, String margin, String minMargin, String fee,
		MarginFloor floor) {
		return calculator.quoteSalePrice(new BigDecimal(unitCost), bundle, new BigDecimal(margin), BigDecimal.ZERO,
			new BigDecimal(minMargin), new BigDecimal(fee), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, floor);
	}

	@Test
	@DisplayName("예 B: 총 매입가 68,862원·마진 3%·최소마진 0·수수료 11% → 목표가 80,100원을 하한가 82,000원으로 올린다")
	void exampleB_floorRaisesTargetPrice() {
		var result = quote("34431", 2, "3", "0", "11", FLOOR_5_5000);

		assertThat(result.salePrice()).isEqualByComparingTo("82000");
		assertThat(result.roundedPrice()).isEqualByComparingTo("80100");
		assertThat(result.floorApplied()).isTrue();
		assertThat(result.floorRaisedFrom()).isEqualByComparingTo("80100");
		assertThat(netProfit(result.salePrice(), "68862", "11")).isGreaterThanOrEqualTo(new BigDecimal("4100"));
	}

	@Test
	@DisplayName("예 C: 총 매입가 150,000원·마진 0·최소마진 0 → 하한액 쪽 174,157.3원이 작아 174,200원")
	void exampleC_smallerOfTwoFloorsWins() {
		var result = quote("150000", 1, "0", "0", "11", FLOOR_5_5000);

		assertThat(result.salePrice()).isEqualByComparingTo("174200");
		assertThat(result.floorApplied()).isTrue();
		assertThat(netProfit(result.salePrice(), "150000", "11")).isGreaterThanOrEqualTo(new BigDecimal("5000"));
	}

	@Test
	@DisplayName("하한율·하한액이 모두 비면 지금 식과 1원도 다르지 않다")
	void emptyFloorKeepsCurrentResult() {
		var before = calculator.quoteSalePrice(new BigDecimal("34431"), 2, new BigDecimal("3"), BigDecimal.ZERO,
			BigDecimal.ZERO, new BigDecimal("11"), BigDecimal.ZERO, BigDecimal.ZERO);
		var after = quote("34431", 2, "3", "0", "11", MarginFloor.NONE);

		assertThat(after).isEqualTo(before);
		assertThat(after.salePrice()).isEqualByComparingTo("80100");
		assertThat(after.floorApplied()).isFalse();
	}

	@Test
	@DisplayName("하한율만 있으면 하한율만 쓴다 — 예 B 81,978.6원 → 82,000원")
	void onlyRateFloor() {
		var result = quote("34431", 2, "3", "0", "11", new MarginFloor(new BigDecimal("5"), null));

		assertThat(result.salePrice()).isEqualByComparingTo("82000");
	}

	@Test
	@DisplayName("하한액만 있으면 하한액만 쓴다 — 예 B 82,991.0원 → 83,000원")
	void onlyAmountFloor() {
		var result = quote("34431", 2, "3", "0", "11", new MarginFloor(null, new BigDecimal("5000")));

		assertThat(result.salePrice()).isEqualByComparingTo("83000");
	}

	@Test
	@DisplayName("예 C를 하한율만으로 계산하면 178,571.4원 → 178,600원")
	void onlyRateFloorExampleC() {
		var result = quote("150000", 1, "0", "0", "11", new MarginFloor(new BigDecimal("5"), null));

		assertThat(result.salePrice()).isEqualByComparingTo("178600");
	}

	@Test
	@DisplayName("목표가가 하한가보다 높으면 그대로 두고 하한 적용으로 표시하지 않는다")
	void floorBelowTargetDoesNothing() {
		var withFloor = quote("34431", 2, "15", "15000", "11", FLOOR_5_5000);
		var without = quote("34431", 2, "15", "15000", "11", MarginFloor.NONE);

		assertThat(withFloor.salePrice()).isEqualByComparingTo(without.salePrice());
		assertThat(withFloor.salePrice()).isEqualByComparingTo("93100");
		assertThat(withFloor.floorApplied()).isFalse();
		assertThat(withFloor.reason()).doesNotContain("마진 하한");
	}

	@Test
	@DisplayName("하한 적용 근거는 기존 최소마진 보장 문구와 구분되고 올리기 전·후 가격을 남긴다")
	void floorReasonIsDistinctFromMinimumMarginReason() {
		var result = quote("34431", 2, "3", "0", "11", FLOOR_5_5000);

		assertThat(result.reason()).contains("마진 하한 적용").contains("80100원").contains("82000원")
			.doesNotContain("최소마진 보장");
	}

	@Test
	@DisplayName("최소 판매가(minimumPrice)에도 하한가가 반영된다 — 배치 검토의 최저가 표시")
	void minimumPriceIncludesFloor() {
		var result = quote("34431", 2, "3", "0", "11", FLOOR_5_5000);

		assertThat(result.minimumPrice()).isGreaterThanOrEqualTo(new BigDecimal("81978"));
	}

	@Test
	@DisplayName("어떤 배치 값·수수료에서도 순이익이 min(판매가 × 5%, 5,000원) 이상이다")
	void netProfitNeverBelowFloor() {
		String[] fees = {"8", "11", "18"};
		String[] margins = {"0", "3", "15"};
		String[] minMargins = {"0", "2500", "15000"};
		for (int total = 1000; total <= 400000; total += 1237) {
			for (String fee : fees) {
				for (String margin : margins) {
					for (String minMargin : minMargins) {
						var result = quote(String.valueOf(total), 1, margin, minMargin, fee, FLOOR_5_5000);
						BigDecimal net = netProfit(result.salePrice(), String.valueOf(total), fee);
						BigDecimal floor = result.salePrice().multiply(new BigDecimal("0.05"))
							.min(new BigDecimal("5000"));
						assertThat(net).as("total=%d fee=%s margin=%s min=%s sale=%s", total, fee, margin,
							minMargin, result.salePrice()).isGreaterThanOrEqualTo(floor);
					}
				}
			}
		}
	}

	@Test
	@DisplayName("하한율과 수수료의 합이 100% 이상이면 계산을 거부한다")
	void rejectsRateFloorThatLeavesNothing() {
		assertThatThrownBy(() -> quote("10000", 1, "0", "0", "18", new MarginFloor(new BigDecimal("82"), null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("하한율");
	}

	private static BigDecimal netProfit(BigDecimal salePrice, String totalBuy, String feeRate) {
		BigDecimal kept = BigDecimal.ONE.subtract(new BigDecimal(feeRate).divide(BigDecimal.valueOf(100), 4,
			RoundingMode.HALF_UP));
		return salePrice.multiply(kept).subtract(new BigDecimal(totalBuy));
	}
}

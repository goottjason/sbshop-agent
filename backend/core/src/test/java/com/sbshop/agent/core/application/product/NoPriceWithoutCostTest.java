package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.product.dto.PricingInputs;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 원가가 없으면 마켓에 가격을 보내지 않는다.
 *
 * <p>원가 0 은 "공짜"가 아니라 <b>품절이라 가격을 못 읽었거나 링크가 죽었다</b>는 뜻이다.
 * 그걸 0 으로 계산하면 마진·수수료만 얹힌 쓰레기 값(예: 182,300원 → 11,000원)이 마켓에 나간다.
 * 2026-08-31 OCD 배치에서 실제로 31건이 이렇게 덮였다.
 */
class NoPriceWithoutCostTest {

	@Test
	@DisplayName("원가가 0 이면 가격을 계산하지 않는다 — null 이어야 마켓 클라이언트가 가격을 건너뛴다")
	void zeroCostYieldsNoPrice() {
		assertThat(ProductMarketSyncService.hasUsableCost(
			new PricingInputs(BigDecimal.ZERO, 1, new BigDecimal("25"), BigDecimal.ZERO,
				new BigDecimal("5000")))).isFalse();
	}

	@Test
	@DisplayName("원가가 null 이어도 가격을 계산하지 않는다")
	void nullCostYieldsNoPrice() {
		assertThat(ProductMarketSyncService.hasUsableCost(
			new PricingInputs(null, 1, new BigDecimal("25"), BigDecimal.ZERO,
				new BigDecimal("5000")))).isFalse();
	}

	@Test
	@DisplayName("원가가 음수여도 거부한다 — 계산 사고로 음수가 들어올 수 있다")
	void negativeCostYieldsNoPrice() {
		assertThat(ProductMarketSyncService.hasUsableCost(
			new PricingInputs(new BigDecimal("-1"), 1, new BigDecimal("25"), BigDecimal.ZERO,
				new BigDecimal("5000")))).isFalse();
	}

	@Test
	@DisplayName("정상 원가면 가격을 계산한다 — 멀쩡한 경로를 막지 않는다")
	void normalCostYieldsPrice() {
		assertThat(ProductMarketSyncService.hasUsableCost(
			new PricingInputs(new BigDecimal("30000"), 1, new BigDecimal("25"), BigDecimal.ZERO,
				new BigDecimal("5000")))).isTrue();
	}
}

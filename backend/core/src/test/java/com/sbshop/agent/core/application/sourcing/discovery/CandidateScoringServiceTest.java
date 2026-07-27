package com.sbshop.agent.core.application.sourcing.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스코어링 규칙을 고정한다.
 *
 * <p>가장 중요한 성질 두 가지:
 * <ol>
 *   <li><b>결측 신호는 0점이 아니다.</b> 네이버 자격증명이 없으면 검색량 신호가 없는데,
 *       이를 0점으로 치면 <i>모든</i> 후보가 같은 만큼 깎여 순위는 그대로여야 정상이다.
 *       0점 처리하면 신호가 있는 후보/없는 후보를 섞을 때 순위가 신호 가용성에 좌우된다.</li>
 *   <li><b>수익성은 점수가 아니라 탈락 조건이다.</b> 아무리 인기 있어도 마진 미달이면 뺀다.</li>
 * </ol>
 */
class CandidateScoringServiceTest {

	private final CandidateScoringService service =
		new CandidateScoringService(new MarginCalculator(), new ObjectMapper());

	private SourcingConfig config() {
		return SourcingConfig.createDefault();
	}

	private SourcingCandidate candidate(BigDecimal price, Integer sales, Integer reviews,
		BigDecimal rating, Integer rank) {
		return SourcingCandidate.builder()
			.vendor(VendorType.IHB)
			.externalId("1")
			.sourceUrl("https://kr.iherb.com/pr/x/1")
			.brand("Test Brand")
			.nameKo("테스트 상품")
			.discountPrice(price)
			.listPrice(price)
			.discountPct(0)
			.sales30d(sales)
			.reviewCount(reviews)
			.rating(rating)
			.rankPosition(rank)
			.build();
	}

	@Test
	@DisplayName("신호가 갖춰진 인기 상품은 높은 점수를 받는다")
	void scoresPopularProductHigh() {
		SourcingCandidate c = candidate(new BigDecimal("20000"), 50000, 34000,
			new BigDecimal("4.8"), 1);

		String reject = service.score(c, config(), SalesHistorySnapshot.empty());

		assertThat(reject).isNull();
		assertThat(c.getTotalScore()).isNotNull();
		assertThat(c.getTotalScore().doubleValue()).isGreaterThan(70);
	}

	@Test
	@DisplayName("결측 신호는 가중치에서 빠진다 — 0점으로 깎지 않는다")
	void missingSignalsAreExcludedFromWeightNotZeroed() {
		// 동일한 iHerb 신호. 한쪽만 국내 수요 신호가 없다.
		SourcingCandidate withDemand = candidate(new BigDecimal("20000"), 50000, 34000,
			new BigDecimal("4.8"), 1);
		withDemand.applyDemandSignals(30000, 500, new BigDecimal("35000"), new BigDecimal("40000"), "테스트");

		SourcingCandidate withoutDemand = candidate(new BigDecimal("20000"), 50000, 34000,
			new BigDecimal("4.8"), 1);

		service.score(withDemand, config(), SalesHistorySnapshot.empty());
		service.score(withoutDemand, config(), SalesHistorySnapshot.empty());

		// 신호가 없다고 점수가 반토막 나면 안 된다(0점 처리라면 40점 가까이 벌어진다).
		double gap = Math.abs(withDemand.getTotalScore().doubleValue()
			- withoutDemand.getTotalScore().doubleValue());
		assertThat(gap).isLessThan(25);
		// 결측 항목은 근거에 남아야 사용자가 "왜 이 점수인지" 알 수 있다.
		assertThat(withoutDemand.getScoreBreakdown()).contains("missing");
		assertThat(withoutDemand.getScoreBreakdown()).contains("searchVolume");
	}

	@Test
	@DisplayName("마진 미달은 점수와 무관하게 탈락 — 인기 상품이어도 팔면 손해다")
	void rejectsBelowMinimumMargin() {
		SourcingConfig cfg = config();
		cfg.update(null, null, null, null, true, new BigDecimal("20"),
			new BigDecimal("100000"), null, null, null, null, null, null, null, null);

		SourcingCandidate c = candidate(new BigDecimal("5000"), 50000, 34000,
			new BigDecimal("4.9"), 1);

		String reject = service.score(c, cfg, SalesHistorySnapshot.empty());

		assertThat(reject).contains("최소");
		assertThat(c.getTotalScore()).isNull();
	}

	@Test
	@DisplayName("국내 최저가를 크게 웃돌면 가격 경쟁 불가로 탈락")
	void rejectsWhenPricedAboveDomesticFloor() {
		SourcingCandidate c = candidate(new BigDecimal("50000"), 50000, 34000,
			new BigDecimal("4.8"), 1);
		// 국내 시세(중앙값) 20,000원 — 우리 판매가는 배송비·수수료 포함이라 훨씬 높아진다.
		c.applyDemandSignals(30000, 500, new BigDecimal("15000"), new BigDecimal("20000"), "테스트");

		String reject = service.score(c, config(), SalesHistorySnapshot.empty());

		assertThat(reject).contains("국내 시세");
	}

	@Test
	@DisplayName("수익성 가드를 끄면 마진 미달도 통과한다")
	void profitGuardCanBeDisabled() {
		SourcingConfig cfg = config();
		cfg.update(null, null, null, null, false, null,
			new BigDecimal("100000"), null, null, null, null, null, null, null, null);

		SourcingCandidate c = candidate(new BigDecimal("5000"), 50000, 34000,
			new BigDecimal("4.9"), 1);

		assertThat(service.score(c, cfg, SalesHistorySnapshot.empty())).isNull();
	}

	@Test
	@DisplayName("매입가가 없으면 판매가를 못 내므로 탈락")
	void rejectsWhenBuyPriceUnknown() {
		SourcingCandidate c = candidate(null, 50000, 34000, new BigDecimal("4.8"), 1);

		assertThat(service.score(c, config(), SalesHistorySnapshot.empty()))
			.contains("매입가");
	}

	@Test
	@DisplayName("경쟁상품이 많을수록 점수가 낮아진다(역방향 신호)")
	void competitionIsInverse() {
		SourcingCandidate lowComp = candidate(new BigDecimal("20000"), 10000, 5000,
			new BigDecimal("4.5"), 10);
		lowComp.applyDemandSignals(10000, 50, new BigDecimal("38000"), new BigDecimal("40000"), "키워드");

		SourcingCandidate highComp = candidate(new BigDecimal("20000"), 10000, 5000,
			new BigDecimal("4.5"), 10);
		highComp.applyDemandSignals(10000, 90000, new BigDecimal("38000"), new BigDecimal("40000"), "키워드");

		service.score(lowComp, config(), SalesHistorySnapshot.empty());
		service.score(highComp, config(), SalesHistorySnapshot.empty());

		assertThat(lowComp.getTotalScore().doubleValue())
			.isGreaterThan(highComp.getTotalScore().doubleValue());
	}

	@Test
	@DisplayName("자사 판매 이력이 있는 브랜드는 가점을 받는다")
	void brandHistoryAddsScore() {
		SalesHistorySnapshot history = SalesHistorySnapshot.of(
			java.util.Map.of("test brand", 100L, "other", 10L), java.util.Map.of());

		SourcingCandidate known = candidate(new BigDecimal("20000"), 10000, 5000,
			new BigDecimal("4.5"), 10);
		SourcingCandidate unknown = candidate(new BigDecimal("20000"), 10000, 5000,
			new BigDecimal("4.5"), 10);

		service.score(known, config(), history);
		service.score(unknown, config(),
			SalesHistorySnapshot.of(java.util.Map.of("someone else", 100L), java.util.Map.of()));

		assertThat(known.getTotalScore().doubleValue())
			.isGreaterThan(unknown.getTotalScore().doubleValue());
	}

	@Test
	@DisplayName("가격 가드는 최저가가 아니라 중앙값을 기준으로 한다")
	void priceGuardUsesMedianNotLowest() {
		// 실측 사례: "비타민D3" 최저가 250원(소용량·샘플)에 걸려 240정 제품이 전멸했다.
		// 중앙값이 있으면 그쪽을 써야 한다.
		SourcingCandidate c = candidate(new BigDecimal("20000"), 10000, 5000,
			new BigDecimal("4.5"), 10);
		c.applyDemandSignals(10000, 500, new BigDecimal("250"), new BigDecimal("45000"), "비타민D3");

		assertThat(service.score(c, config(), SalesHistorySnapshot.empty())).isNull();
		assertThat(c.priceBenchmark()).isEqualByComparingTo("45000");
	}

	@Test
	@DisplayName("중앙값이 없으면 최저가로 폴백한다")
	void fallsBackToLowestWhenMedianMissing() {
		SourcingCandidate c = candidate(new BigDecimal("50000"), 10000, 5000,
			new BigDecimal("4.5"), 10);
		c.applyDemandSignals(10000, 500, new BigDecimal("20000"), null, "키워드");

		assertThat(service.score(c, config(), SalesHistorySnapshot.empty()))
			.contains("국내 최저가");
	}

	@Test
	@DisplayName("점수 근거에 서브스코어와 기여도가 남는다")
	void breakdownExplainsScore() {
		SourcingCandidate c = candidate(new BigDecimal("20000"), 50000, 34000,
			new BigDecimal("4.8"), 1);

		service.score(c, config(), SalesHistorySnapshot.empty());

		assertThat(c.getScoreBreakdown())
			.contains("parts").contains("sales30d").contains("contribution")
			.contains("estimatedSalePrice");
	}
}

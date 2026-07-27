package com.sbshop.agent.core.application.sourcing.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 후보 스코어링(S3) — 여러 신호를 0~100점 하나로 합치고, 수익성 미달은 탈락시킨다.
 *
 * <p>설계 두 가지가 중요하다.
 *
 * <p><b>1. 신호가 없으면 가중치에서 빼고 정규화한다.</b> 네이버 자격증명이 없거나 iHerb가
 * 판매량을 노출하지 않으면 그 서브스코어는 0이 아니라 <i>결측</i>이다. 0으로 치면 신호가 없는
 * 후보가 전부 하위로 밀려 순위가 신호 가용성에 좌우된다. 사용 가능한 가중치 합으로 나눠
 * "가진 신호 기준의 상대 점수"를 낸다.
 *
 * <p><b>2. 수익성은 점수가 아니라 게이트다.</b> 마진 미달 상품은 아무리 인기 있어도 팔면 손해라
 * 순위를 낮추는 게 아니라 후보에서 뺀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateScoringService {

	/** log 정규화 기준 상한 — 이 값 이상이면 만점. */
	private static final double SALES_LOG_MAX = 5.0;      // 100,000개/30일
	private static final double REVIEW_LOG_MAX = 5.0;     // 100,000건
	private static final double SEARCH_LOG_MAX = 5.0;     // 100,000회/월
	private static final double COMPETITION_LOG_MAX = 5.0; // 100,000개 경쟁상품

	/** 랭킹 점수 산정 시 "이 순위 밖은 동일 취급" 기준. */
	private static final double RANK_HORIZON = 200.0;

	/**
	 * 국내 시세를 비교 기준으로 인정할 최소 비율(매입원가 대비).
	 * 이보다 낮으면 키워드가 다른 상품군을 물어온 것으로 본다 — 근거는 {@code isBenchmarkReliable}.
	 */
	private static final double MIN_PLAUSIBLE_BENCHMARK_RATIO = 0.35;

	private final MarginCalculator marginCalculator;
	private final ObjectMapper objectMapper;

	/**
	 * 후보를 채점해 엔티티에 반영한다.
	 *
	 * @return 탈락 사유. null이면 통과(엔티티에 점수가 반영된 상태).
	 */
	public String score(SourcingCandidate c, SourcingConfig config, SalesHistorySnapshot history) {
		Map<String, Double> weights = parseWeights(config.getScoreWeights());

		// --- 수익성: 점수가 아니라 게이트 ---
		Pricing pricing = estimatePricing(c, config);
		if (Boolean.TRUE.equals(config.getProfitGuardEnabled())) {
			String reject = profitGuardReject(c, config, pricing);
			if (reject != null)
				return reject;
		}

		Map<String, Double> subScores = new LinkedHashMap<>();

		// --- iHerb 신호 ---
		put(subScores, "sales30d", logScore(c.getSales30d(), SALES_LOG_MAX));
		put(subScores, "reviewCount", logScore(c.getReviewCount(), REVIEW_LOG_MAX));
		put(subScores, "rating", ratingScore(c.getRating()));
		put(subScores, "rank", rankScore(c.getRankPosition()));
		put(subScores, "discount", discountScore(c.getDiscountPct()));

		// --- 국내 수요 (자격증명 없으면 결측) ---
		put(subScores, "searchVolume", logScore(c.getMonthlySearchVolume(), SEARCH_LOG_MAX));
		put(subScores, "competition", competitionScore(c.getCompetitorCount()));
		BigDecimal benchmark = c.priceBenchmark();
		put(subScores, "priceEdge",
			benchmark != null && isBenchmarkReliable(benchmark, c)
				? priceEdgeScore(benchmark, pricing.salePrice()) : null);

		// --- 자사 이력 (주문 이력이 없으면 결측) ---
		if (!history.isEmpty()) {
			subScores.put("brandHistory", history.brandScore(c.getBrand()));
			subScores.put("categoryHistory", history.categoryScore(c.getCategorySlug()));
		}

		double weightedSum = 0, usableWeight = 0;
		for (Map.Entry<String, Double> e : subScores.entrySet()) {
			double w = weights.getOrDefault(e.getKey(), 0.0);
			if (w <= 0)
				continue;
			weightedSum += e.getValue() * w;
			usableWeight += w;
		}
		if (usableWeight <= 0) {
			return "채점 가능한 신호가 없습니다";
		}
		double total = (weightedSum / usableWeight) * 100.0;

		c.applyScore(
			BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP),
			buildBreakdown(subScores, weights, usableWeight, total, pricing),
			pricing.salePrice(),
			pricing.marginRate());
		return null;
	}

	// --- 수익성 ---

	/**
	 * 마켓별 판매가는 S4에서 산정한다. 여기서는 게이트 판정용 대표값(기본 수수료)만 낸다.
	 *
	 * <p><b>minMarginPrice를 일부러 넘기지 않는다.</b> {@link MarginCalculator}는 최소 마진에
	 * 미달하면 판매가를 그만큼 <i>끌어올려</i> 마진을 맞춘다. 그 값을 그대로 쓰면 마진 가드는
	 * 항상 통과하는 죽은 코드가 된다(계산기가 이미 조건을 만족시켜 버렸으므로).
	 * 알고 싶은 건 "목표 마진율로 자연스럽게 붙는 마진이 최소 기준을 넘는가"이므로
	 * 보정 없는 <b>자연가</b>로 계산한 뒤 그 마진을 기준과 비교해야 한다.
	 */
	private Pricing estimatePricing(SourcingCandidate c, SourcingConfig config) {
		BigDecimal buyPrice = c.effectiveBuyPrice();
		BigDecimal marginRate = config.getTargetMarginRate() != null
			? config.getTargetMarginRate() : new BigDecimal("20");
		if (buyPrice.signum() <= 0)
			return new Pricing(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

		BigDecimal salePrice = marginCalculator.calculateSalePrice(
			buyPrice, 1, marginRate, config.getCouponRate(), null);
		BigDecimal margin = salePrice.subtract(buyPrice);
		return new Pricing(salePrice, margin, marginRate);
	}

	private String profitGuardReject(SourcingCandidate c, SourcingConfig config, Pricing pricing) {
		if (pricing.salePrice().signum() <= 0)
			return "판매가를 산정할 수 없음(매입가 불명)";

		// 목표 마진율로 자연스럽게 붙는 마진액이 기준 미달이면, 기준을 맞추려고 가격을 올려야 하고
		// 그러면 가격 경쟁력을 잃는다. 그런 상품은 애초에 추천하지 않는다.
		BigDecimal minMargin = config.getMinMarginPrice();
		if (minMargin != null && pricing.margin().compareTo(minMargin) < 0) {
			return "목표 마진율 기준 예상 마진 %s원 < 최소 %s원".formatted(
				pricing.margin().setScale(0, RoundingMode.DOWN), minMargin.setScale(0, RoundingMode.DOWN));
		}

		// 국내 시세를 크게 웃돌면 가격 경쟁이 불가능하다. 신호가 없으면 판단하지 않는다.
		//
		// ⚠️ 기준은 **중앙값**이다. 최저가(lprice)로 재면 광범위 키워드의 절대 최저가가
		//    소용량·샘플 같은 비교 불가 상품에 걸려 멀쩡한 후보를 전부 죽인다
		//    (운영 실측: "비타민D3" 최저가 250원 → 240정 제품이 탈락. 25/25 전멸).
		BigDecimal benchmark = c.priceBenchmark();
		BigDecimal ratio = config.getMaxPriceRatio();
		if (benchmark != null && benchmark.signum() > 0 && ratio != null
			&& isBenchmarkReliable(benchmark, c)) {
			BigDecimal ceiling = benchmark.multiply(ratio);
			if (pricing.salePrice().compareTo(ceiling) > 0) {
				String basis = c.getDomesticMedianPrice() != null ? "국내 시세(중앙값)" : "국내 최저가";
				return "예상 판매가 %s원 > %s %s원의 %s배".formatted(
					pricing.salePrice().setScale(0, RoundingMode.DOWN), basis,
					benchmark.setScale(0, RoundingMode.DOWN), ratio);
			}
		}
		return null;
	}

	/**
	 * 이 시세를 비교 기준으로 믿어도 되는가.
	 *
	 * <p>키워드 검색은 종종 다른 상품군을 물어온다. 문제는 "시세 &lt; 원가"만으로는
	 * <b>키워드 오매칭</b>과 <b>진짜 가격경쟁력 없음</b>을 구분할 수 없다는 것이다 —
	 * 후자는 걸러내야 하는 정상 케이스다. 구분선은 <b>격차의 크기</b>다.
	 *
	 * <p>실측값으로 기준을 잡았다(매입원가 대비 국내 중앙값 비율):
	 * <pre>
	 *   "Gold"      중앙값 10원    / 원가 23,172원 = 0.0004  ← 명백한 오매칭
	 *   "비타민D3"   중앙값 2,820원 / 원가 12,763원 = 0.22    ← 240정 vs 소용량, 오매칭
	 *   "Neuro-Mag" 중앙값 43,000원 / 원가 42,327원 = 1.02    ← 같은 상품군, 신뢰 가능
	 * </pre>
	 * 원가의 {@value #MIN_PLAUSIBLE_BENCHMARK_RATIO}배 미만이면 같은 상품을 본 것으로 보기 어렵다.
	 * 못 믿는 신호로 후보를 떨어뜨리느니 <b>판정하지 않는</b> 편이 낫다.
	 */
	private boolean isBenchmarkReliable(BigDecimal benchmark, SourcingCandidate c) {
		BigDecimal buyPrice = c.effectiveBuyPrice();
		if (buyPrice == null || buyPrice.signum() <= 0)
			return true;
		BigDecimal floor = buyPrice.multiply(BigDecimal.valueOf(MIN_PLAUSIBLE_BENCHMARK_RATIO));
		if (benchmark.compareTo(floor) < 0) {
			log.info("[스코어링] 국내 시세({})가 매입원가({}) 대비 너무 낮아 가격 가드를 적용하지 않음 "
				+ "— 키워드가 다른 상품군을 물었을 가능성: {}",
				benchmark.setScale(0, RoundingMode.DOWN), buyPrice.setScale(0, RoundingMode.DOWN),
				c.getDemandKeyword());
			return false;
		}
		return true;
	}

	// --- 서브스코어 (모두 0.0~1.0, 결측이면 null → 가중치에서 제외) ---

	private void put(Map<String, Double> target, String key, Double value) {
		if (value != null)
			target.put(key, value);
	}

	private Double logScore(Integer value, double logMax) {
		if (value == null)
			return null;             // 결측 — 0점이 아니다
		if (value <= 0)
			return 0.0;
		double v = Math.log10(value) / logMax;
		return clamp(v);
	}

	private Double ratingScore(BigDecimal rating) {
		if (rating == null)
			return null;
		// 3.5 미만은 0, 5.0이 만점. 영양제 평점은 대부분 4점대라 4.0~5.0 구간 해상도가 중요하다.
		return clamp((rating.doubleValue() - 3.5) / 1.5);
	}

	private Double rankScore(Integer position) {
		if (position == null || position <= 0)
			return null;
		return clamp(1.0 - (position / RANK_HORIZON));
	}

	private Double discountScore(Integer discountPct) {
		if (discountPct == null)
			return null;
		// 할인 30% 이상이면 만점 — 매입 원가가 낮아 마진 여유가 생긴다.
		return clamp(discountPct / 30.0);
	}

	/** 경쟁상품 수는 <b>적을수록</b> 좋다(역방향). */
	private Double competitionScore(Integer competitorCount) {
		if (competitorCount == null)
			return null;
		if (competitorCount <= 0)
			return 1.0;
		return clamp(1.0 - (Math.log10(competitorCount) / COMPETITION_LOG_MAX));
	}

	/** 국내 시세(중앙값) 대비 우리 예상 판매가가 얼마나 싼가. 비싸면 0. */
	private Double priceEdgeScore(BigDecimal benchmark, BigDecimal ourPrice) {
		if (benchmark == null || benchmark.signum() <= 0 || ourPrice == null || ourPrice.signum() <= 0)
			return null;
		double edge = (benchmark.doubleValue() - ourPrice.doubleValue()) / benchmark.doubleValue();
		// 30% 이상 저렴하면 만점.
		return clamp(edge / 0.3);
	}

	private double clamp(double v) {
		if (v < 0)
			return 0;
		return Math.min(v, 1.0);
	}

	// --- 근거 ---

	private String buildBreakdown(Map<String, Double> subScores, Map<String, Double> weights,
		double usableWeight, double total, Pricing pricing) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("total", round(total));
		root.put("usableWeight", round(usableWeight));
		root.put("estimatedSalePrice", pricing.salePrice());
		root.put("estimatedMargin", pricing.margin());

		ObjectNode parts = root.putObject("parts");
		for (Map.Entry<String, Double> e : subScores.entrySet()) {
			double w = weights.getOrDefault(e.getKey(), 0.0);
			ObjectNode p = parts.putObject(e.getKey());
			p.put("score", round(e.getValue()));
			p.put("weight", w);
			p.put("contribution", round(w > 0 ? (e.getValue() * w / usableWeight) * 100 : 0));
		}
		// 어떤 신호가 결측이었는지도 남긴다 — 점수가 낮은 이유를 설명할 수 있어야 한다.
		var missing = root.putArray("missing");
		for (String key : weights.keySet()) {
			if (!subScores.containsKey(key))
				missing.add(key);
		}
		try {
			return objectMapper.writeValueAsString(root);
		} catch (Exception e) {
			return "{}";
		}
	}

	private double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	private Map<String, Double> parseWeights(String json) {
		Map<String, Double> defaults = defaultWeights();
		if (json == null || json.isBlank())
			return defaults;
		try {
			Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
			Map<String, Double> out = new LinkedHashMap<>(defaults);
			parsed.forEach((k, v) -> {
				if (v instanceof Number n)
					out.put(String.valueOf(k), n.doubleValue());
			});
			return out;
		} catch (Exception e) {
			log.warn("[스코어링] 가중치 JSON 파싱 실패 — 기본값 사용: {}", e.getMessage());
			return defaults;
		}
	}

	private Map<String, Double> defaultWeights() {
		Map<String, Double> w = new LinkedHashMap<>();
		w.put("sales30d", 20.0);
		w.put("reviewCount", 10.0);
		w.put("rating", 8.0);
		w.put("rank", 7.0);
		w.put("discount", 5.0);
		w.put("searchVolume", 20.0);
		w.put("competition", 10.0);
		w.put("priceEdge", 10.0);
		w.put("brandHistory", 5.0);
		w.put("categoryHistory", 5.0);
		return w;
	}

	private record Pricing(BigDecimal salePrice, BigDecimal margin, BigDecimal marginRate) {
	}
}

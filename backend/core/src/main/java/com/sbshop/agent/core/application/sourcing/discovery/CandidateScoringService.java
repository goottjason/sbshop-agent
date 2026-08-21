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

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateScoringService {
	private static final double SALES_LOG_MAX = 5.0;
	private static final double REVIEW_LOG_MAX = 5.0;
	private static final double SEARCH_LOG_MAX = 5.0;
	private static final double COMPETITION_LOG_MAX = 5.0;

	private static final double RANK_HORIZON = 200.0;

	private static final double MIN_PLAUSIBLE_BENCHMARK_RATIO = 0.35;

	private final MarginCalculator marginCalculator;
	private final ObjectMapper objectMapper;

	public String score(SourcingCandidate c, SourcingConfig config, SalesHistorySnapshot history) {
		Map<String, Double> weights = parseWeights(config.getScoreWeights());

		Pricing pricing = estimatePricing(c, config);
		if (Boolean.TRUE.equals(config.getProfitGuardEnabled())) {
			String reject = profitGuardReject(c, config, pricing);
			if (reject != null)
				return reject;
		}

		Map<String, Double> subScores = new LinkedHashMap<>();

		put(subScores, "sales30d", logScore(c.getSales30d(), SALES_LOG_MAX));
		put(subScores, "reviewCount", logScore(c.getReviewCount(), REVIEW_LOG_MAX));
		put(subScores, "rating", ratingScore(c.getRating()));
		put(subScores, "rank", rankScore(c.getRankPosition()));
		put(subScores, "discount", discountScore(c.getDiscountPct()));

		put(subScores, "searchVolume", logScore(c.getMonthlySearchVolume(), SEARCH_LOG_MAX));
		put(subScores, "competition", competitionScore(c.getCompetitorCount()));
		BigDecimal benchmark = c.priceBenchmark();
		put(subScores, "priceEdge",
			benchmark != null && isBenchmarkReliable(benchmark, c)
				? priceEdgeScore(benchmark, pricing.salePrice()) : null);

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

		BigDecimal minMargin = config.getMinMarginPrice();
		if (minMargin != null && pricing.margin().compareTo(minMargin) < 0) {
			return "목표 마진율 기준 예상 마진 %s원 < 최소 %s원".formatted(
				pricing.margin().setScale(0, RoundingMode.DOWN), minMargin.setScale(0, RoundingMode.DOWN));
		}

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

	private void put(Map<String, Double> target, String key, Double value) {
		if (value != null)
			target.put(key, value);
	}

	private Double logScore(Integer value, double logMax) {
		if (value == null)
			return null;
		if (value <= 0)
			return 0.0;
		double v = Math.log10(value) / logMax;
		return clamp(v);
	}

	private Double ratingScore(BigDecimal rating) {
		if (rating == null)
			return null;

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

		return clamp(discountPct / 30.0);
	}

	private Double competitionScore(Integer competitorCount) {
		if (competitorCount == null)
			return null;
		if (competitorCount <= 0)
			return 1.0;
		return clamp(1.0 - (Math.log10(competitorCount) / COMPETITION_LOG_MAX));
	}

	private Double priceEdgeScore(BigDecimal benchmark, BigDecimal ourPrice) {
		if (benchmark == null || benchmark.signum() <= 0 || ourPrice == null || ourPrice.signum() <= 0)
			return null;
		double edge = (benchmark.doubleValue() - ourPrice.doubleValue()) / benchmark.doubleValue();

		return clamp(edge / 0.3);
	}

	private double clamp(double v) {
		if (v < 0)
			return 0;
		return Math.min(v, 1.0);
	}

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

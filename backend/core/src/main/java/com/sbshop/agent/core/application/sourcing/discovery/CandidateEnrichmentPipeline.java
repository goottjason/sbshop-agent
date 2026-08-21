package com.sbshop.agent.core.application.sourcing.discovery;

import com.sbshop.agent.core.application.sourcing.customs.CustomsEligibilityService;
import com.sbshop.agent.core.application.sourcing.dto.KeywordVolume;
import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.application.sourcing.dto.ShoppingStats;
import com.sbshop.agent.core.application.sourcing.port.KeywordVolumePort;
import com.sbshop.agent.core.application.sourcing.port.ProductDetailCrawlerPort;
import com.sbshop.agent.core.application.sourcing.port.ShoppingMarketPort;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.sourcing.BannedIngredient;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import com.sbshop.agent.core.domain.sourcing.component.SearchKeywordDeriver;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateEnrichmentPipeline {
	private static final int HISTORY_DAYS = 90;

	private static final int DEEP_PASS_MULTIPLIER = 3;
	private static final int DEEP_PASS_MIN = 30;

	private final ProductDetailCrawlerPort detailCrawler;
	private final CustomsEligibilityService customsService;
	private final KeywordVolumePort keywordVolumePort;
	private final ShoppingMarketPort shoppingMarketPort;
	private final CandidateScoringService scoringService;
	private final CandidatePersistTxService persistTxService;
	private final OrderLineItemRepository orderLineItemRepository;

	public Outcome process(List<SourcingCandidate> candidates, SourcingConfig config) {
		List<String> warnings = new ArrayList<>();
		if (candidates.isEmpty())
			return new Outcome(0, 0, 0, 0, warnings);

		SalesHistorySnapshot history = loadHistory(warnings);
		List<BannedIngredient> bannedList = customsService.loadActiveList();
		if (bannedList.isEmpty()) {
			warnings.add("반입차단 성분 DB가 비어 있어 통관 판정을 내릴 수 없습니다 — 전건 확인필요 처리됩니다.");
		}

		List<SourcingCandidate> ranked = preRank(candidates, history);

		int deepLimit = deepPassLimit(config);
		int scored = 0, excluded = 0, blocked = 0, review = 0;

		for (int i = 0; i < ranked.size(); i++) {
			SourcingCandidate c = ranked.get(i);
			boolean deep = i < deepLimit;

			if (!deep) {
				c.exclude("상위 %d건 밖(정밀 심사 미수행)".formatted(deepLimit));
				persistTxService.save(c);
				excluded++;
				continue;
			}

			CustomsEligibilityService.Result verdict = evaluateCustoms(c, bannedList, warnings);
			c.applyCustomsVerdict(verdict.verdict(), verdict.reason(), c.getIngredientsRaw());
			if (verdict.isBlocked()) {
				c.exclude("통관 불가: " + verdict.reason());
				persistTxService.save(c);
				blocked++;
				excluded++;
				continue;
			}
			if (verdict.verdict() == CustomsVerdict.REVIEW)
				review++;

			applyDemandSignals(c);

			String reject = scoringService.score(c, config, history);
			if (reject != null) {
				c.exclude(reject);
				excluded++;
			} else {
				scored++;
			}
			persistTxService.save(c);
		}

		log.info("[소싱파이프라인] 정밀심사 {} · 추천대상 {} · 통관차단 {} · 확인필요 {} · 제외 {}",
			Math.min(deepLimit, ranked.size()), scored, blocked, review, excluded);
		return new Outcome(scored, excluded, blocked, review, warnings);
	}

	private List<SourcingCandidate> preRank(List<SourcingCandidate> candidates,
		SalesHistorySnapshot history) {
		Map<SourcingCandidate, Double> prelim = new IdentityHashMap<>();
		for (SourcingCandidate c : candidates) {
			prelim.put(c, prelimScore(c, history));
		}
		List<SourcingCandidate> sorted = new ArrayList<>(candidates);
		sorted.sort((a, b) -> Double.compare(prelim.get(b), prelim.get(a)));
		return sorted;
	}

	private double prelimScore(SourcingCandidate c, SalesHistorySnapshot history) {
		double score = 0;
		if (c.getSales30d() != null && c.getSales30d() > 0)
			score += Math.min(Math.log10(c.getSales30d()) / 5.0, 1.0) * 40;
		if (c.getReviewCount() != null && c.getReviewCount() > 0)
			score += Math.min(Math.log10(c.getReviewCount()) / 5.0, 1.0) * 25;
		if (c.getRating() != null)
			score += Math.max(0, Math.min((c.getRating().doubleValue() - 3.5) / 1.5, 1.0)) * 15;
		if (c.getRankPosition() != null && c.getRankPosition() > 0)
			score += Math.max(0, 1.0 - c.getRankPosition() / 200.0) * 10;
		score += history.brandScore(c.getBrand()) * 10;
		return score;
	}

	private int deepPassLimit(SourcingConfig config) {
		int target = config.getRecommendCount() != null ? config.getRecommendCount() : 20;
		return Math.max(DEEP_PASS_MIN, target * DEEP_PASS_MULTIPLIER);
	}

	private CustomsEligibilityService.Result evaluateCustoms(SourcingCandidate c,
		List<BannedIngredient> bannedList, List<String> warnings) {
		if (c.getIngredientsRaw() == null || c.getIngredientsRaw().isBlank()) {
			ProductDetailDto detail = detailCrawler.fetchDetail(c.getSourceUrl());
			if (detail.ok() && detail.ingredientsRaw() != null) {
				c.applyCustomsVerdict(CustomsVerdict.UNKNOWN, null, detail.ingredientsRaw());
			} else {
				warnings.add("상세 크롤 실패(%s): %s".formatted(c.getExternalId(), detail.error()));
			}
		}
		if (bannedList.isEmpty()) {
			return new CustomsEligibilityService.Result(CustomsVerdict.REVIEW,
				"반입차단 성분 DB가 비어 있어 판정할 수 없습니다(동기화 필요).", List.of());
		}
		return customsService.evaluateAgainst(c.getIngredientsRaw(), c.getNameKo(), bannedList);
	}

	private void applyDemandSignals(SourcingCandidate c) {
		List<String> keywords = SearchKeywordDeriver.deriveCandidates(c.getNameKo(), c.getBrand());
		if (keywords.isEmpty()) {
			String fallback = SearchKeywordDeriver.derive(c.getNameKo(), c.getBrand());
			if (fallback.isBlank())
				return;
			keywords = List.of(fallback);
		}

		String volumeKeyword = keywords.get(0);
		Integer bestVolume = null;
		if (keywordVolumePort.isEnabled()) {
			for (String kw : keywords) {
				Integer v = pickSeedVolume(keywordVolumePort.lookup(kw), kw);
				if (v != null && (bestVolume == null || v > bestVolume)) {
					bestVolume = v;
					volumeKeyword = kw;
				}
			}
		}

		String priceKeyword = specificKeyword(c, keywords);
		Integer competitors = null;
		BigDecimal lowPrice = null;
		BigDecimal medianPrice = null;
		if (shoppingMarketPort.isEnabled()) {
			Optional<ShoppingStats> stats = shoppingMarketPort.lookup(priceKeyword);
			if (stats.isPresent()) {
				competitors = stats.get().totalCount();
				lowPrice = stats.get().lowestPrice();
				medianPrice = stats.get().medianPrice();
			}
		}
		c.applyDemandSignals(bestVolume, competitors, lowPrice, medianPrice, volumeKeyword);
	}

	private String specificKeyword(SourcingCandidate c, List<String> keywords) {
		String specific = SearchKeywordDeriver.deriveSpecific(c.getNameKo(), c.getBrand());
		if (!specific.isBlank())
			return specific;
		return keywords.stream().max(Comparator.comparingInt(String::length))
			.orElse(keywords.get(0));
	}

	private Integer pickSeedVolume(List<KeywordVolume> volumes, String keyword) {
		if (volumes.isEmpty())
			return null;
		String norm = keyword.replaceAll("\\s+", "").toLowerCase();
		return volumes.stream()
			.filter(v -> v.keyword() != null
				&& v.keyword().replaceAll("\\s+", "").toLowerCase().equals(norm))
			.map(KeywordVolume::total)
			.findFirst()
			.orElseGet(() -> volumes.stream().mapToInt(KeywordVolume::total).max().orElse(0));
	}

	private SalesHistorySnapshot loadHistory(List<String> warnings) {
		try {
			LocalDateTime since = LocalDateTime.now().minusDays(HISTORY_DAYS);
			Map<String, Long> brands = new HashMap<>();
			for (Object[] row : orderLineItemRepository.aggregateBrandSalesSince(since)) {
				brands.merge(String.valueOf(row[0]), toLong(row[2]), Long::sum);
			}
			Map<String, Long> categories = new HashMap<>();
			for (Object[] row : orderLineItemRepository.aggregateCategorySalesSince(since)) {
				categories.merge(String.valueOf(row[0]), toLong(row[2]), Long::sum);
			}
			return SalesHistorySnapshot.of(brands, categories);
		} catch (Exception e) {
			warnings.add("자사 판매 이력 집계 실패 — 해당 신호 없이 채점: " + e.getMessage());
			log.warn("[소싱파이프라인] 판매 이력 집계 실패", e);
			return SalesHistorySnapshot.empty();
		}
	}

	private long toLong(Object v) {
		return v instanceof Number n ? n.longValue() : 0L;
	}

	public record Outcome(int scored, int excluded, int customsBlocked, int customsReview,
		List<String> warnings) {
	}
}

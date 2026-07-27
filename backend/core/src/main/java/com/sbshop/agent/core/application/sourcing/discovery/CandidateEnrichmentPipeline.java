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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 통관 게이트(S2) → 국내 수요 신호 → 스코어링(S3)을 후보별로 수행한다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 후보마다 상세 페이지 크롤(브라우저 렌더 ~10초)과 네이버 API
 * 왕복이 들어간다. 후보 30건이면 5분 이상이라 트랜잭션이 감싸면 커넥션을 그 내내 붙잡는다.
 * DB 쓰기는 {@link CandidatePersistTxService}가 후보 단위 짧은 트랜잭션으로 커밋한다.
 *
 * <p>비용 통제: 상세 크롤은 <b>스코어 상위 후보에만</b> 돌린다. 발굴 회차마다 500여 건이 올라오는데
 * 전건 상세 크롤은 2시간이 넘는다. 순서를 뒤집어 (1) 크롤 없는 신호로 1차 채점 →
 * (2) 상위 N건만 상세 크롤 + 통관 판정 + 수요 조회 → (3) 재채점한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateEnrichmentPipeline {

	/** 자사 이력 집계 기간. */
	private static final int HISTORY_DAYS = 90;

	/**
	 * 정밀 처리(상세 크롤 + 수요 API) 대상 배수.
	 * 추천 목표가 20건이면 60건까지만 정밀 처리한다 — 통관 차단·수요 미달로 빠지는 만큼의 여유.
	 */
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

		// 1차 채점: 크롤 없이 가능한 신호(iHerb + 자사이력)만으로 순위를 매긴다.
		List<SourcingCandidate> ranked = preRank(candidates, history);

		int deepLimit = deepPassLimit(config);
		int scored = 0, excluded = 0, blocked = 0, review = 0;

		for (int i = 0; i < ranked.size(); i++) {
			SourcingCandidate c = ranked.get(i);
			boolean deep = i < deepLimit;

			if (!deep) {
				// 정밀 처리 대상 밖 — 조용히 사라지지 않게 사유를 남긴다.
				c.exclude("상위 %d건 밖(정밀 심사 미수행)".formatted(deepLimit));
				persistTxService.save(c);
				excluded++;
				continue;
			}

			// S2: 통관 게이트
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

			// 국내 수요 신호 (선택적 — 자격증명 없으면 결측으로 남는다)
			applyDemandSignals(c);

			// S3: 스코어링 + 수익성 게이트
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

	// --- 단계 ---

	/**
	 * 상세 크롤 없이 가능한 신호만으로 1차 정렬한다.
	 *
	 * <p>여기서의 목적은 정확한 점수가 아니라 <b>정밀 심사 대상을 고르는 것</b>이다.
	 * 판매량·리뷰·평점·랭킹은 목록 카드에서 이미 다 얻었으므로 추가 비용이 0이다.
	 */
	private List<SourcingCandidate> preRank(List<SourcingCandidate> candidates,
		SalesHistorySnapshot history) {
		// 후보당 점수를 한 번만 계산해 두고 정렬한다(비교자 안에서 계산하면 O(n log n)번 재계산된다).
		Map<SourcingCandidate, Double> prelim = new java.util.IdentityHashMap<>();
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

	/** 성분 원문이 없으면 상세를 크롤해 채운 뒤 판정한다. */
	private CustomsEligibilityService.Result evaluateCustoms(SourcingCandidate c,
		List<BannedIngredient> bannedList, List<String> warnings) {

		if (c.getIngredientsRaw() == null || c.getIngredientsRaw().isBlank()) {
			ProductDetailDto detail = detailCrawler.fetchDetail(c.getSourceUrl());
			if (detail.ok() && detail.ingredientsRaw() != null) {
				c.applyCustomsVerdict(CustomsVerdict.UNKNOWN, null, detail.ingredientsRaw());
			} else {
				// 크롤 실패를 조용히 넘기면 성분 미상 상품이 PASS로 흘러간다.
				warnings.add("상세 크롤 실패(%s): %s".formatted(c.getExternalId(), detail.error()));
			}
		}
		if (bannedList.isEmpty()) {
			return new CustomsEligibilityService.Result(CustomsVerdict.REVIEW,
				"반입차단 성분 DB가 비어 있어 판정할 수 없습니다(동기화 필요).", List.of());
		}
		return customsService.evaluateAgainst(c.getIngredientsRaw(), c.getNameKo(), bannedList);
	}

	/**
	 * 국내 수요 신호. <b>검색량과 가격은 서로 다른 키워드로 조회한다.</b>
	 *
	 * <p>둘의 목적이 다르기 때문이다:
	 * <ul>
	 *   <li><b>검색량</b>은 카테고리 수요를 재는 것이라 <i>일반적인</i> 말이어야 한다("비타민D3")</li>
	 *   <li><b>가격</b>은 같은 상품과 비교해야 하므로 <i>구체적인</i> 말이어야 한다
	 *       (브랜드 + 제품 핵심부)</li>
	 * </ul>
	 *
	 * <p>둘을 한 키워드로 묶었더니 실측에서 무너졌다 — 검색량을 최대화하면 "Gold" 같은 일반어가
	 * 뽑히고 그 중앙값이 10원이 나와 멀쩡한 후보가 전부 탈락했다.
	 */
	private void applyDemandSignals(SourcingCandidate c) {
		List<String> keywords = SearchKeywordDeriver.deriveCandidates(c.getNameKo(), c.getBrand());
		if (keywords.isEmpty()) {
			String fallback = SearchKeywordDeriver.derive(c.getNameKo(), c.getBrand());
			if (fallback.isBlank())
				return;
			keywords = List.of(fallback);
		}

		// 검색량: 후보 중 가장 많이 검색되는 말(= 카테고리 수요).
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

		// 가격·경쟁: 브랜드를 붙인 가장 구체적인 말(= 같은 상품군).
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

	/** 가격 비교용 키워드 — 한글 브랜드 + 제품 핵심부. 못 만들면 가장 긴 후보를 쓴다. */
	private String specificKeyword(SourcingCandidate c, List<String> keywords) {
		String specific = SearchKeywordDeriver.deriveSpecific(c.getNameKo(), c.getBrand());
		if (!specific.isBlank())
			return specific;
		return keywords.stream().max(java.util.Comparator.comparingInt(String::length))
			.orElse(keywords.get(0));
	}

	/**
	 * 시드 키워드의 검색량. 정확히 일치하는 항목이 없으면 <b>최댓값</b>을 쓴다 —
	 * 키워드도구는 시드를 정규화해 돌려주는 일이 있어 문자열 일치에만 의존하면 자주 결측이 된다.
	 */
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

	/** 파이프라인 처리 결과. */
	public record Outcome(int scored, int excluded, int customsBlocked, int customsReview,
		List<String> warnings) {
	}
}

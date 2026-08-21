package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 소싱 자동화 설정 — 단일 행 테이블.
 *
 * <p>추천 개수·크롤 범위·스코어 가중치처럼 운영하면서 계속 조정하게 되는 값을 코드가 아니라
 * DB에 둔다(재배포 없이 바꾸기 위함). 행이 없으면 {@link #createDefault()}로 만든다.
 */
@Entity
@Table(name = "sb_sourcing_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourcingConfig extends BaseEntity {

	public static final String DEFAULT_CATEGORIES = "supplements,grocery,sports-nutrition,herbs-homeopathy";

	/**
	 * 서브스코어 가중치 기본값. 합계 100.
	 * 국내 수요(검색량·경쟁·가격경쟁력) 40 / iHerb 신호 50 / 자사 이력 10.
	 */
	public static final String DEFAULT_SCORE_WEIGHTS = """
		{"sales30d":20,"reviewCount":10,"rating":8,"rank":7,"discount":5,\
		"searchVolume":20,"competition":10,"priceEdge":10,\
		"brandHistory":5,"categoryHistory":5}""";

	/** 한 번에 추천할 상품 개수 (사용자 요구: 10~30, 변동 가능). */
	@Column(name = "recommend_count", nullable = false)
	private Integer recommendCount = 20;

	/** 크롤 대상 iHerb 카테고리 slug, 쉼표 구분. */
	@Column(name = "categories", length = 500, nullable = false)
	private String categories = DEFAULT_CATEGORIES;

	@Column(name = "pages_per_category", nullable = false)
	private Integer pagesPerCategory = 3;

	/** 서브스코어 가중치 JSON. */
	@Column(name = "score_weights", columnDefinition = "text")
	private String scoreWeights = DEFAULT_SCORE_WEIGHTS;

	// --- 수익성 가드 ---

	@Column(name = "profit_guard_enabled", nullable = false)
	private Boolean profitGuardEnabled = true;

	@Column(name = "target_margin_rate", precision = 5, scale = 2)
	private BigDecimal targetMarginRate = new BigDecimal("20.00");

	/** 최소 마진(원). 이 아래면 추천에서 탈락. */
	@Column(name = "min_margin_price", precision = 15, scale = 2)
	private BigDecimal minMarginPrice = new BigDecimal("3000");

	/** 예상 판매가가 국내 최저가의 이 배수를 넘으면 가격 경쟁 불가로 보고 탈락. */
	@Column(name = "max_price_ratio", precision = 4, scale = 2)
	private BigDecimal maxPriceRatio = new BigDecimal("1.30");

	/** 매입 쿠폰 할인율(%) — iHerb 상시 할인 반영. */
	@Column(name = "coupon_rate", precision = 5, scale = 2)
	private BigDecimal couponRate = BigDecimal.ZERO;

	// --- 필터 ---

	/** 사용자 거절 후 재추천 금지 기간(일). */
	@Column(name = "reject_cooldown_days", nullable = false)
	private Integer rejectCooldownDays = 90;

	/** 광고 노출(sponsored) 상품 제외 여부 — 랭킹이 유기적 인기가 아니다. */
	@Column(name = "exclude_sponsored", nullable = false)
	private Boolean excludeSponsored = true;

	/** 최소 리뷰 수. 검증되지 않은 신상품을 거르는 기준. */
	@Column(name = "min_review_count", nullable = false)
	private Integer minReviewCount = 50;

	@Column(name = "min_rating", precision = 3, scale = 2)
	private BigDecimal minRating = new BigDecimal("4.0");

	// --- 스케줄 ---

	@Column(name = "schedule_enabled", nullable = false)
	private Boolean scheduleEnabled = true;

	@Column(name = "schedule_cron", length = 50, nullable = false)
	private String scheduleCron = "0 0 3 * * *";

	public static SourcingConfig createDefault() {
		return new SourcingConfig();
	}

	public java.util.List<String> categoryList() {
		if (categories == null || categories.isBlank())
			return java.util.List.of();
		return java.util.Arrays.stream(categories.split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	public void update(Integer recommendCount, String categories, Integer pagesPerCategory,
		String scoreWeights, Boolean profitGuardEnabled, BigDecimal targetMarginRate,
		BigDecimal minMarginPrice, BigDecimal maxPriceRatio, BigDecimal couponRate,
		Integer rejectCooldownDays, Boolean excludeSponsored, Integer minReviewCount,
		BigDecimal minRating, Boolean scheduleEnabled, String scheduleCron) {
		if (recommendCount != null && recommendCount > 0)
			this.recommendCount = recommendCount;
		if (categories != null && !categories.isBlank())
			this.categories = categories;
		if (pagesPerCategory != null && pagesPerCategory > 0)
			this.pagesPerCategory = pagesPerCategory;
		if (scoreWeights != null && !scoreWeights.isBlank())
			this.scoreWeights = scoreWeights;
		if (profitGuardEnabled != null)
			this.profitGuardEnabled = profitGuardEnabled;
		if (targetMarginRate != null)
			this.targetMarginRate = targetMarginRate;
		if (minMarginPrice != null)
			this.minMarginPrice = minMarginPrice;
		if (maxPriceRatio != null)
			this.maxPriceRatio = maxPriceRatio;
		if (couponRate != null)
			this.couponRate = couponRate;
		if (rejectCooldownDays != null && rejectCooldownDays >= 0)
			this.rejectCooldownDays = rejectCooldownDays;
		if (excludeSponsored != null)
			this.excludeSponsored = excludeSponsored;
		if (minReviewCount != null && minReviewCount >= 0)
			this.minReviewCount = minReviewCount;
		if (minRating != null)
			this.minRating = minRating;
		if (scheduleEnabled != null)
			this.scheduleEnabled = scheduleEnabled;
		if (scheduleCron != null && !scheduleCron.isBlank())
			this.scheduleCron = scheduleCron;
	}
}

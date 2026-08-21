package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_sourcing_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourcingConfig extends BaseEntity {
	public static final String DEFAULT_CATEGORIES = "supplements,grocery,sports-nutrition,herbs-homeopathy";

	public static final String DEFAULT_SCORE_WEIGHTS = """
		{"sales30d":20,"reviewCount":10,"rating":8,"rank":7,"discount":5,\
		"searchVolume":20,"competition":10,"priceEdge":10,\
		"brandHistory":5,"categoryHistory":5}""";

	@Column(name = "recommend_count", nullable = false)
	private Integer recommendCount = 20;

	@Column(name = "categories", length = 500, nullable = false)
	private String categories = DEFAULT_CATEGORIES;

	@Column(name = "pages_per_category", nullable = false)
	private Integer pagesPerCategory = 3;

	@Column(name = "score_weights", columnDefinition = "text")
	private String scoreWeights = DEFAULT_SCORE_WEIGHTS;

	@Column(name = "profit_guard_enabled", nullable = false)
	private Boolean profitGuardEnabled = true;

	@Column(name = "target_margin_rate", precision = 5, scale = 2)
	private BigDecimal targetMarginRate = new BigDecimal("20.00");

	@Column(name = "min_margin_price", precision = 15, scale = 2)
	private BigDecimal minMarginPrice = new BigDecimal("3000");

	@Column(name = "max_price_ratio", precision = 4, scale = 2)
	private BigDecimal maxPriceRatio = new BigDecimal("1.30");

	@Column(name = "coupon_rate", precision = 5, scale = 2)
	private BigDecimal couponRate = BigDecimal.ZERO;

	@Column(name = "reject_cooldown_days", nullable = false)
	private Integer rejectCooldownDays = 90;

	@Column(name = "exclude_sponsored", nullable = false)
	private Boolean excludeSponsored = true;

	@Column(name = "min_review_count", nullable = false)
	private Integer minReviewCount = 50;

	@Column(name = "min_rating", precision = 3, scale = 2)
	private BigDecimal minRating = new BigDecimal("4.0");

	@Column(name = "schedule_enabled", nullable = false)
	private Boolean scheduleEnabled = true;

	@Column(name = "schedule_cron", length = 50, nullable = false)
	private String scheduleCron = "0 0 3 * * *";

	public static SourcingConfig createDefault() {
		return new SourcingConfig();
	}

	public List<String> categoryList() {
		if (categories == null || categories.isBlank())
			return List.of();
		return Arrays.stream(categories.split(","))
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

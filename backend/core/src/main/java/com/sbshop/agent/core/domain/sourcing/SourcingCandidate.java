package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.enums.CandidateStatus;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "sb_sourcing_candidate", uniqueConstraints = @UniqueConstraint(name = "uk_candidate_vendor_external", columnNames = {
	"vendor", "external_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourcingCandidate extends BaseEntity {
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "vendor", length = 10, nullable = false)
	private VendorType vendor;

	@Column(name = "external_id", length = 50, nullable = false)
	private String externalId;

	@Column(name = "source_url", columnDefinition = "text", nullable = false)
	private String sourceUrl;

	@Column(name = "part_number", length = 50)
	private String partNumber;

	@Column(name = "brand", length = 100)
	private String brand;

	@Column(name = "brand_code", length = 20)
	private String brandCode;

	@Column(name = "name_ko", length = 500)
	private String nameKo;

	@Column(name = "category_slug", length = 50)
	private String categorySlug;

	@Column(name = "image_url", columnDefinition = "text")
	private String imageUrl;

	@Column(name = "list_price", precision = 15, scale = 2)
	private BigDecimal listPrice;

	@Column(name = "discount_price", precision = 15, scale = 2)
	private BigDecimal discountPrice;

	@Column(name = "discount_pct")
	private Integer discountPct;

	@Column(name = "rating", precision = 3, scale = 2)
	private BigDecimal rating;

	@Column(name = "review_count")
	private Integer reviewCount;

	@Column(name = "sales_30d")
	private Integer sales30d;

	@Column(name = "rank_position")
	private Integer rankPosition;

	@Column(name = "is_sponsored", nullable = false)
	private Boolean isSponsored = false;

	@Column(name = "is_out_of_stock", nullable = false)
	private Boolean isOutOfStock = false;

	@Column(name = "is_discontinued", nullable = false)
	private Boolean isDiscontinued = false;

	@Column(name = "monthly_search_volume")
	private Integer monthlySearchVolume;

	@Column(name = "competitor_count")
	private Integer competitorCount;

	@Column(name = "domestic_low_price", precision = 15, scale = 2)
	private BigDecimal domesticLowPrice;

	@Column(name = "domestic_median_price", precision = 15, scale = 2)
	private BigDecimal domesticMedianPrice;

	@Column(name = "demand_keyword", length = 200)
	private String demandKeyword;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "customs_verdict", length = 20, nullable = false)
	private CustomsVerdict customsVerdict = CustomsVerdict.UNKNOWN;

	@Column(name = "customs_reason", columnDefinition = "text")
	private String customsReason;

	@Column(name = "ingredients_raw", columnDefinition = "text")
	private String ingredientsRaw;

	@Column(name = "customs_checked_at")
	private LocalDateTime customsCheckedAt;

	@Column(name = "total_score", precision = 6, scale = 2)
	private BigDecimal totalScore;

	@Column(name = "score_breakdown", columnDefinition = "text")
	private String scoreBreakdown;

	@Column(name = "estimated_sale_price", precision = 15, scale = 0)
	private BigDecimal estimatedSalePrice;

	@Column(name = "estimated_margin_rate", precision = 6, scale = 2)
	private BigDecimal estimatedMarginRate;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "candidate_status", length = 20, nullable = false)
	private CandidateStatus candidateStatus = CandidateStatus.NEW;

	@Column(name = "exclude_reason", length = 200)
	private String excludeReason;

	@Column(name = "rejected_at")
	private LocalDateTime rejectedAt;

	@Column(name = "discovered_at")
	private LocalDateTime discoveredAt;

	@Column(name = "last_seen_at")
	private LocalDateTime lastSeenAt;

	@Builder
	private SourcingCandidate(VendorType vendor, String externalId, String sourceUrl, String partNumber,
		String brand, String brandCode, String nameKo, String categorySlug, String imageUrl,
		BigDecimal listPrice, BigDecimal discountPrice, Integer discountPct,
		BigDecimal rating, Integer reviewCount, Integer sales30d, Integer rankPosition,
		Boolean isSponsored, Boolean isOutOfStock, Boolean isDiscontinued) {
		this.vendor = vendor;
		this.externalId = externalId;
		this.sourceUrl = sourceUrl;
		this.partNumber = partNumber;
		this.brand = brand;
		this.brandCode = brandCode;
		this.nameKo = nameKo;
		this.categorySlug = categorySlug;
		this.imageUrl = imageUrl;
		this.listPrice = listPrice;
		this.discountPrice = discountPrice;
		this.discountPct = discountPct;
		this.rating = rating;
		this.reviewCount = reviewCount;
		this.sales30d = sales30d;
		this.rankPosition = rankPosition;
		this.isSponsored = isSponsored != null && isSponsored;
		this.isOutOfStock = isOutOfStock != null && isOutOfStock;
		this.isDiscontinued = isDiscontinued != null && isDiscontinued;
		this.candidateStatus = CandidateStatus.NEW;
		this.customsVerdict = CustomsVerdict.UNKNOWN;
		this.discoveredAt = LocalDateTime.now();
		this.lastSeenAt = this.discoveredAt;
	}

	public void refreshFromDiscovery(BigDecimal listPrice, BigDecimal discountPrice, Integer discountPct,
		BigDecimal rating, Integer reviewCount, Integer sales30d, Integer rankPosition,
		boolean isSponsored, boolean isOutOfStock, boolean isDiscontinued,
		String nameKo, String imageUrl, String categorySlug) {
		this.listPrice = listPrice;
		this.discountPrice = discountPrice;
		this.discountPct = discountPct;
		this.rating = rating;
		this.reviewCount = reviewCount;
		this.sales30d = sales30d;
		this.rankPosition = rankPosition;
		this.isSponsored = isSponsored;
		this.isOutOfStock = isOutOfStock;
		this.isDiscontinued = isDiscontinued;
		if (nameKo != null && !nameKo.isBlank())
			this.nameKo = nameKo;
		if (imageUrl != null && !imageUrl.isBlank())
			this.imageUrl = imageUrl;
		if (categorySlug != null && !categorySlug.isBlank())
			this.categorySlug = categorySlug;
		this.lastSeenAt = LocalDateTime.now();
	}

	public void applyCustomsVerdict(CustomsVerdict verdict, String reason, String ingredientsRaw) {
		this.customsVerdict = verdict;
		this.customsReason = reason;
		if (ingredientsRaw != null && !ingredientsRaw.isBlank())
			this.ingredientsRaw = ingredientsRaw;
		this.customsCheckedAt = LocalDateTime.now();
	}

	public void applyDemandSignals(Integer monthlySearchVolume, Integer competitorCount,
		BigDecimal domesticLowPrice, BigDecimal domesticMedianPrice, String demandKeyword) {
		this.monthlySearchVolume = monthlySearchVolume;
		this.competitorCount = competitorCount;
		this.domesticLowPrice = domesticLowPrice;
		this.domesticMedianPrice = domesticMedianPrice;
		this.demandKeyword = demandKeyword;
	}

	public BigDecimal priceBenchmark() {
		if (domesticMedianPrice != null && domesticMedianPrice.signum() > 0)
			return domesticMedianPrice;
		return domesticLowPrice;
	}

	public void applyScore(BigDecimal totalScore, String scoreBreakdown,
		BigDecimal estimatedSalePrice, BigDecimal estimatedMarginRate) {
		this.totalScore = totalScore;
		this.scoreBreakdown = scoreBreakdown;
		this.estimatedSalePrice = estimatedSalePrice;
		this.estimatedMarginRate = estimatedMarginRate;
		this.candidateStatus = CandidateStatus.SCORED;
		this.excludeReason = null;
	}

	public void exclude(String reason) {
		this.candidateStatus = CandidateStatus.EXCLUDED;
		this.excludeReason = reason;
	}

	public void reject() {
		this.candidateStatus = CandidateStatus.REJECTED;
		this.rejectedAt = LocalDateTime.now();
	}

	public void markDrafted() {
		this.candidateStatus = CandidateStatus.DRAFTED;
	}

	public void markPublished() {
		this.candidateStatus = CandidateStatus.PUBLISHED;
	}

	public boolean isUserDecided() {
		return candidateStatus == CandidateStatus.REJECTED
			|| candidateStatus == CandidateStatus.DRAFTED
			|| candidateStatus == CandidateStatus.PUBLISHED;
	}

	public BigDecimal effectiveBuyPrice() {
		if (discountPrice != null && discountPrice.signum() > 0)
			return discountPrice;
		return listPrice != null ? listPrice : BigDecimal.ZERO;
	}
}

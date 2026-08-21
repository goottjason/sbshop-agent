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

/**
 * 소싱 후보 — iHerb 베스트셀러에서 발굴한 "팔 수도 있는 상품" 1건.
 *
 * <p>목록 페이지 카드 1장에서 스코어링에 필요한 신호가 전부 나오므로(평점·리뷰수·30일 판매량·랭킹),
 * 발굴 단계에서는 상세 페이지를 열지 않는다. 상세 크롤(성분표)은 통관 게이트 대상만 수행한다.
 *
 * <p>재수집은 {@code (vendor, externalId)} 유니크 키로 <b>upsert</b>한다 — 가격·랭킹·리뷰수는
 * 갱신하되 사용자 판단({@code REJECTED})과 진행 상태({@code DRAFTED}/{@code PUBLISHED})는 보존한다.
 */
@Entity
@Table(name = "sb_sourcing_candidate", uniqueConstraints = @UniqueConstraint(name = "uk_candidate_vendor_external", columnNames = {
	"vendor", "external_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourcingCandidate extends BaseEntity {

	// --- 식별 ---

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "vendor", length = 10, nullable = false)
	private VendorType vendor;

	/** 벤더 내 상품 ID (iHerb product id). 중복 등록 판정 키. */
	@Column(name = "external_id", length = 50, nullable = false)
	private String externalId;

	@Column(name = "source_url", columnDefinition = "text", nullable = false)
	private String sourceUrl;

	@Column(name = "part_number", length = 50)
	private String partNumber;

	// --- 상품 기본 ---

	@Column(name = "brand", length = 100)
	private String brand;

	@Column(name = "brand_code", length = 20)
	private String brandCode;

	/** iHerb 한국 사이트의 한글 상품명. 상품명 생성의 1차 소스. */
	@Column(name = "name_ko", length = 500)
	private String nameKo;

	/** iHerb 카테고리 slug (supplements / grocery / sports-nutrition / herbs-homeopathy). */
	@Column(name = "category_slug", length = 50)
	private String categorySlug;

	@Column(name = "image_url", columnDefinition = "text")
	private String imageUrl;

	// --- 가격 (원화) ---
	// kr.iherb.com이 원화로 표기하므로 환산하지 않는다. MarginCalculator의 buyPrice와 단위가 같다.

	@Column(name = "list_price", precision = 15, scale = 2)
	private BigDecimal listPrice;

	@Column(name = "discount_price", precision = 15, scale = 2)
	private BigDecimal discountPrice;

	@Column(name = "discount_pct")
	private Integer discountPct;

	// --- 수요 신호 (iHerb) ---

	@Column(name = "rating", precision = 3, scale = 2)
	private BigDecimal rating;

	@Column(name = "review_count")
	private Integer reviewCount;

	/** "30일 동안 N개 판매" 파싱값. iHerb가 노출할 때만 존재한다. */
	@Column(name = "sales_30d")
	private Integer sales30d;

	@Column(name = "rank_position")
	private Integer rankPosition;

	/** 광고 노출 상품 — 랭킹이 유기적 인기가 아니므로 랭킹 점수를 신뢰하면 안 된다. */
	@Column(name = "is_sponsored", nullable = false)
	private Boolean isSponsored = false;

	@Column(name = "is_out_of_stock", nullable = false)
	private Boolean isOutOfStock = false;

	@Column(name = "is_discontinued", nullable = false)
	private Boolean isDiscontinued = false;

	// --- 수요 신호 (국내) ---

	/** 네이버 검색광고 keywordstool 월간 검색량(PC+모바일). 자격증명 없으면 null. */
	@Column(name = "monthly_search_volume")
	private Integer monthlySearchVolume;

	/** 네이버 쇼핑검색 total — 경쟁 상품 수. 클수록 레드오션. */
	@Column(name = "competitor_count")
	private Integer competitorCount;

	/**
	 * 네이버 쇼핑검색 최저가(원). <b>표시용</b>이다.
	 *
	 * <p>가격 경쟁력 판정에는 쓰지 않는다 — 광범위 키워드의 절대 최저가는 소용량·샘플 같은
	 * 비교 불가 상품에 걸린다(실측: "비타민D3" 최저가 250원). 판정은
	 * {@link #domesticMedianPrice}로 한다.
	 */
	@Column(name = "domestic_low_price", precision = 15, scale = 2)
	private BigDecimal domesticLowPrice;

	/** 네이버 쇼핑검색 결과의 <b>중앙값</b>(원). 가격 경쟁력 판정 기준 — 시장가 대리값. */
	@Column(name = "domestic_median_price", precision = 15, scale = 2)
	private BigDecimal domesticMedianPrice;

	@Column(name = "demand_keyword", length = 200)
	private String demandKeyword;

	// --- 통관 게이트 ---

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "customs_verdict", length = 20, nullable = false)
	private CustomsVerdict customsVerdict = CustomsVerdict.UNKNOWN;

	/** 판정 근거 — 검출 성분과 식약처 지정 사유. UI에 그대로 보여준다. */
	@Column(name = "customs_reason", columnDefinition = "text")
	private String customsReason;

	/** 상세 페이지에서 추출한 성분 원문(한글). 사용자가 직접 확인할 수 있게 보존한다. */
	@Column(name = "ingredients_raw", columnDefinition = "text")
	private String ingredientsRaw;

	@Column(name = "customs_checked_at")
	private LocalDateTime customsCheckedAt;

	// --- 스코어링 ---

	@Column(name = "total_score", precision = 6, scale = 2)
	private BigDecimal totalScore;

	/** 서브스코어별 점수·원본값 JSON. "왜 추천됐는지"를 UI에서 설명하기 위한 근거. */
	@Column(name = "score_breakdown", columnDefinition = "text")
	private String scoreBreakdown;

	/** 마켓별 예상 판매가 중 대표값(원). 수익성 가드·가격 경쟁력 산정 결과. */
	@Column(name = "estimated_sale_price", precision = 15, scale = 0)
	private BigDecimal estimatedSalePrice;

	@Column(name = "estimated_margin_rate", precision = 6, scale = 2)
	private BigDecimal estimatedMarginRate;

	// --- 파이프라인 상태 ---

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "candidate_status", length = 20, nullable = false)
	private CandidateStatus candidateStatus = CandidateStatus.NEW;

	/** EXCLUDED 사유 — 조용히 사라지지 않게 항상 남긴다. */
	@Column(name = "exclude_reason", length = 200)
	private String excludeReason;

	@Column(name = "rejected_at")
	private LocalDateTime rejectedAt;

	@Column(name = "discovered_at")
	private LocalDateTime discoveredAt;

	@Column(name = "last_seen_at")
	private LocalDateTime lastSeenAt;

	// --- 팩토리 / 갱신 ---

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

	/**
	 * 재수집 시 시세성 필드만 갱신한다.
	 *
	 * <p>사용자 판단(REJECTED)과 진행 상태(DRAFTED/PUBLISHED)는 건드리지 않는다 —
	 * 매일 도는 스케줄러가 사용자가 거절한 상품을 되살리면 같은 상품이 무한히 재추천된다.
	 * 통관 판정도 보존한다(성분은 자주 바뀌지 않고, 재판정은 상세 크롤 비용이 든다).
	 */
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

	/** 가격 비교 기준가 — 중앙값 우선, 없으면 최저가. */
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

	/** 파이프라인 자동 제외. 사유를 반드시 남긴다. */
	public void exclude(String reason) {
		this.candidateStatus = CandidateStatus.EXCLUDED;
		this.excludeReason = reason;
	}

	/** 사용자 거절 — 쿨다운 기산점을 남긴다. */
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

	/** 사용자가 손댄 후보인가 — 재수집이 상태를 되돌리면 안 되는 대상. */
	public boolean isUserDecided() {
		return candidateStatus == CandidateStatus.REJECTED
			|| candidateStatus == CandidateStatus.DRAFTED
			|| candidateStatus == CandidateStatus.PUBLISHED;
	}

	/** 실제 매입 단가(원). 할인가가 있으면 할인가, 없으면 정가. */
	public BigDecimal effectiveBuyPrice() {
		if (discountPrice != null && discountPrice.signum() > 0)
			return discountPrice;
		return listPrice != null ? listPrice : BigDecimal.ZERO;
	}
}

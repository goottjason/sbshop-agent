package com.sbshop.agent.api.dto.sourcing;

import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.application.sourcing.publish.DraftPublishUseCase;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 소싱 자동화 API의 요청·응답 DTO 모음.
 *
 * <p>파일을 하나로 묶은 이유: 전부 이 기능 전용 얕은 매핑이라 파일당 20줄짜리 레코드가
 * 10개 넘게 흩어지는 것보다 한곳에서 계약을 보는 편이 읽기 쉽다.
 */
public final class SourcingDtos {

	private SourcingDtos() {}

	// ── 후보 ────────────────────────────────────────────────────────────────

	/**
	 * 추천 목록의 한 줄.
	 *
	 * @param scoreBreakdown 점수 근거 JSON 원문. 프론트가 "왜 추천됐는지"를 펼쳐 보여준다.
	 * @param customsReason  통관 판정 사유. REVIEW면 사용자가 읽고 판단해야 한다.
	 */
	public record CandidateResponse(
		Long id, String vendor, String externalId, String sourceUrl,
		String brand, String nameKo, String categorySlug, String imageUrl,
		BigDecimal listPrice, BigDecimal discountPrice, Integer discountPct,
		BigDecimal rating, Integer reviewCount, Integer sales30d, Integer rankPosition,
		Integer monthlySearchVolume, Integer competitorCount, BigDecimal domesticLowPrice,
		BigDecimal domesticMedianPrice, String demandKeyword,
		String customsVerdict, String customsReason, String ingredientsRaw,
		BigDecimal totalScore, String scoreBreakdown,
		BigDecimal estimatedSalePrice, BigDecimal estimatedMarginRate,
		String candidateStatus, String excludeReason,
		LocalDateTime discoveredAt, LocalDateTime lastSeenAt) {

		public static CandidateResponse from(SourcingCandidate c) {
			return new CandidateResponse(
				c.getId(), c.getVendor() != null ? c.getVendor().name() : null,
				c.getExternalId(), c.getSourceUrl(), c.getBrand(), c.getNameKo(),
				c.getCategorySlug(), c.getImageUrl(),
				c.getListPrice(), c.getDiscountPrice(), c.getDiscountPct(),
				c.getRating(), c.getReviewCount(), c.getSales30d(), c.getRankPosition(),
				c.getMonthlySearchVolume(), c.getCompetitorCount(), c.getDomesticLowPrice(),
				c.getDomesticMedianPrice(), c.getDemandKeyword(),
				c.getCustomsVerdict() != null ? c.getCustomsVerdict().name() : null,
				c.getCustomsReason(), c.getIngredientsRaw(),
				c.getTotalScore(), c.getScoreBreakdown(),
				c.getEstimatedSalePrice(), c.getEstimatedMarginRate(),
				c.getCandidateStatus() != null ? c.getCandidateStatus().name() : null,
				c.getExcludeReason(), c.getDiscoveredAt(), c.getLastSeenAt());
		}
	}

	public record DiscoveryRunResponse(
		String startedAt, String finishedAt, int crawled, int created, int updated,
		int excluded, int scored, int customsBlocked, int customsReview,
		int cooldownReleased, List<String> warnings) {

		public static DiscoveryRunResponse from(DiscoverySummary s) {
			return new DiscoveryRunResponse(
				String.valueOf(s.startedAt()), String.valueOf(s.finishedAt()),
				s.crawled(), s.created(), s.updated(), s.excluded(), s.scored(),
				s.customsBlocked(), s.customsReview(), s.cooldownReleased(), s.warnings());
		}
	}

	// ── 초안 ────────────────────────────────────────────────────────────────

	public record CreateDraftsRequest(List<Long> candidateIds) {
	}

	public record MarketDraftResponse(
		Long id, String marketType, String productName, String categoryId, String categoryPath,
		BigDecimal salePrice, BigDecimal channelFeeRate, String keywords, String noticeFields,
		String extraFields, String missingFields, boolean valid, boolean enabled,
		String publishError, String marketIdentifiers) {

		public static MarketDraftResponse from(MarketDraft m) {
			return new MarketDraftResponse(
				m.getId(), m.getMarketType().name(), m.getProductName(), m.getCategoryId(),
				m.getCategoryPath(), m.getSalePrice(), m.getChannelFeeRate(), m.getKeywords(),
				m.getNoticeFields(), m.getExtraFields(), m.getMissingFields(),
				m.isValid(), m.isEnabled(), m.getPublishError(), m.getMarketIdentifiers());
		}
	}

	public record DraftResponse(
		Long id, Long candidateId, String baseNameKo, String originalName, String brand,
		Integer bundleQty, BigDecimal marginRate, BigDecimal costPrice, String sourceUrl,
		String origin, String hsCode, String barcode, BigDecimal weightG, BigDecimal capacity,
		String measureUnit, String category, String detailHtml, String sourceImages,
		String hostedImages, String ingredientsKo, String usageKo, String cautionKo,
		boolean customsAck, String draftStatus, String enrichNote, Long productId,
		List<MarketDraftResponse> marketDrafts) {

		public static DraftResponse from(ProductDraft d) {
			return new DraftResponse(
				d.getId(), d.getCandidateId(), d.getBaseNameKo(), d.getOriginalName(), d.getBrand(),
				d.getBundleQty(), d.getMarginRate(), d.getCostPrice(), d.getSourceUrl(),
				d.getOrigin(), d.getHsCode(), d.getBarcode(), d.getWeightG(), d.getCapacity(),
				d.getMeasureUnit() != null ? d.getMeasureUnit().name() : null,
				d.getCategory(), d.getDetailHtml(), d.getSourceImages(), d.getHostedImages(),
				d.getIngredientsKo(), d.getUsageKo(), d.getCautionKo(),
				Boolean.TRUE.equals(d.getCustomsAck()),
				d.getDraftStatus() != null ? d.getDraftStatus().name() : null,
				d.getEnrichNote(), d.getProductId(),
				d.getMarketDrafts().stream().map(MarketDraftResponse::from).toList());
		}
	}

	public record CreateDraftsResponse(List<DraftResponse> drafts, List<DraftFailure> failures) {
	}

	public record DraftFailure(Long candidateId, String name, String reason) {
	}

	/** 검수 수정 요청. null 필드는 "변경 없음"이다(부분 수정). */
	public record UpdateDraftRequest(
		String baseNameKo, Integer bundleQty, BigDecimal marginRate, BigDecimal costPrice,
		String origin, String hsCode, String barcode, BigDecimal weightG, BigDecimal capacity,
		String measureUnit, String detailHtml, Boolean customsAck,
		List<UpdateMarketDraftRequest> marketDrafts) {
	}

	public record UpdateMarketDraftRequest(
		String marketType, String productName, String categoryId, String categoryPath,
		BigDecimal salePrice, List<String> keywords, Boolean enabled) {
	}

	public record PublishDraftResponse(
		Long draftId, Long productId, String sbCode, long successCount, int totalCount,
		List<MarketOutcomeResponse> outcomes) {

		public static PublishDraftResponse from(DraftPublishUseCase.PublishResult r) {
			return new PublishDraftResponse(
				r.draftId(), r.productId(), r.sbCode(), r.successCount(), r.outcomes().size(),
				r.outcomes().stream().map(MarketOutcomeResponse::from).toList());
		}
	}

	public record MarketOutcomeResponse(String marketType, boolean ok, String identifiers,
		String error) {

		static MarketOutcomeResponse from(DraftPublishUseCase.MarketOutcome o) {
			return new MarketOutcomeResponse(o.marketType().name(), o.ok(), o.identifiers(),
				o.error());
		}
	}

	// ── 설정 ────────────────────────────────────────────────────────────────

	public record ConfigResponse(
		Integer recommendCount, String categories, Integer pagesPerCategory, String scoreWeights,
		Boolean profitGuardEnabled, BigDecimal targetMarginRate, BigDecimal minMarginPrice,
		BigDecimal maxPriceRatio, BigDecimal couponRate, Integer rejectCooldownDays,
		Boolean excludeSponsored, Integer minReviewCount, BigDecimal minRating,
		Boolean scheduleEnabled, String scheduleCron) {

		public static ConfigResponse from(SourcingConfig c) {
			return new ConfigResponse(
				c.getRecommendCount(), c.getCategories(), c.getPagesPerCategory(),
				c.getScoreWeights(), c.getProfitGuardEnabled(), c.getTargetMarginRate(),
				c.getMinMarginPrice(), c.getMaxPriceRatio(), c.getCouponRate(),
				c.getRejectCooldownDays(), c.getExcludeSponsored(), c.getMinReviewCount(),
				c.getMinRating(), c.getScheduleEnabled(), c.getScheduleCron());
		}
	}

	public record ConfigUpdateRequest(
		Integer recommendCount, String categories, Integer pagesPerCategory, String scoreWeights,
		Boolean profitGuardEnabled, BigDecimal targetMarginRate, BigDecimal minMarginPrice,
		BigDecimal maxPriceRatio, BigDecimal couponRate, Integer rejectCooldownDays,
		Boolean excludeSponsored, Integer minReviewCount, BigDecimal minRating,
		Boolean scheduleEnabled, String scheduleCron) {
	}
}

package com.sbshop.agent.core.application.sourcing.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingConfigService;
import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.application.sourcing.port.ProductDetailCrawlerPort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import com.sbshop.agent.core.domain.sourcing.SourcingConfig;
import com.sbshop.agent.core.domain.sourcing.component.SearchKeywordDeriver;
import com.sbshop.agent.core.domain.sourcing.enums.CustomsVerdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 후보 → 등록 초안(S4). 사용자가 후보를 고른 순간 4개 마켓 초안을 전부 채운다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 상세 크롤(브라우저 렌더) · 이미지 다운로드/R2 업로드 ·
 * LLM 호출 · 마켓 카테고리 API가 전부 들어간다. DB 쓰기는 {@link DraftPersistTxService}에 위임한다
 * (기존 {@code ProductCreateUseCase}와 같은 규율 — F-PSRC-8).
 *
 * <p>인리치먼트 단계는 <b>실패해도 초안을 만든다.</b> 이미지 업로드가 실패하거나 LLM이 죽어도
 * 사용자는 검수 화면에서 손으로 채워 등록할 수 있어야 한다. 다만 무엇이 실패했는지는
 * {@code enrichNote}에 남겨 검수 화면에 띄운다 — 조용히 빈 값으로 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftEnrichmentUseCase {

	private static final List<MarketType> TARGET_MARKETS = List.of(
		MarketType.COUPANG, MarketType.SMART_STORE, MarketType.ELEVEN_STREET, MarketType.CAFE24);

	/** 상세 이미지 상한. 전부 올리면 R2 비용·상세HTML 길이가 불필요하게 커진다. */
	private static final int MAX_IMAGES = 8;

	private final ProductDetailCrawlerPort detailCrawler;
	private final ProductTextService productTextService;
	private final BundleQuantityOptimizer bundleOptimizer;
	private final MarketDraftBuilder marketDraftBuilder;
	private final DetailHtmlBuilder detailHtmlBuilder;
	private final ImageDownloadClient imageDownloadClient;
	private final ImageStorageClient imageStorageClient;
	private final DraftPersistTxService draftPersistTxService;
	private final SourcingConfigService configService;
	private final ObjectMapper objectMapper;

	/** 후보 여러 건을 초안으로 만든다. 한 건이 실패해도 나머지는 계속 진행한다. */
	public Result enrichAll(List<SourcingCandidate> candidates) {
		SourcingConfig config = configService.getOrCreate();
		List<ProductDraft> created = new ArrayList<>();
		List<Failure> failures = new ArrayList<>();

		for (SourcingCandidate candidate : candidates) {
			try {
				created.add(enrich(candidate, config));
			} catch (Exception e) {
				log.error("[초안생성] 실패 candidateId={}", candidate.getId(), e);
				failures.add(new Failure(candidate.getId(), candidate.getNameKo(), e.getMessage()));
			}
		}
		return new Result(created, failures);
	}

	public ProductDraft enrich(SourcingCandidate candidate, SourcingConfig config) {
		List<String> notes = new ArrayList<>();

		// 1) 상세 크롤 — 성분·중량·UPC·이미지. 통관 판정 때 이미 받았을 수 있으나
		//    이미지·스펙은 그때 후보에 저장하지 않으므로 여기서 다시 받는다.
		ProductDetailDto detail = detailCrawler.fetchDetail(candidate.getSourceUrl());
		if (!detail.ok())
			notes.add("상세 크롤 불완전: " + detail.error());

		// 2) 묶음 수량 추천
		BigDecimal unitCost = candidate.effectiveBuyPrice();
		Double weightG = detail.shippingWeightGrams() != null
			? detail.shippingWeightGrams().doubleValue() : null;
		BundleQuantityOptimizer.Recommendation bundle = bundleOptimizer.recommend(unitCost, weightG);
		notes.add("묶음 추천: " + bundle.reason());

		// 3) 상품명·키워드 (LLM 우선, 규칙 폴백)
		ProductTextService.Result text = productTextService.generate(
			candidate, detail.mainIngredients(), detail.packageQuantity(),
			MeasureUnit.EA.getDescription());
		if ("rule-based".equals(text.source()))
			notes.add("상품명·키워드를 규칙 기반으로 생성(LLM 미사용/실패)");

		// 4) 초안 본체
		ProductDraft draft = ProductDraft.builder()
			.candidateId(candidate.getId())
			.baseNameKo(text.baseName())
			.originalName(candidate.getNameKo())
			.brand(candidate.getBrand())
			.bundleQty(bundle.quantity())
			.marginRate(config.getTargetMarginRate())
			.costPrice(unitCost)
			.sourceUrl(candidate.getSourceUrl())
			.vendor(candidate.getVendor() != null ? candidate.getVendor().name() : "IHB")
			.origin(resolveOrigin(detail))
			.hsCode(hsCodeFor(candidate.getCategorySlug()))
			.barcode(detail.upc())
			.weightG(detail.shippingWeightGrams())
			.capacity(capacityOf(detail))
			.measureUnit(MeasureUnit.EA)
			.category(candidate.getCategorySlug())
			.sourceImages(toJson(limit(detail.images())))
			.ingredientsKo(firstNonBlank(detail.ingredientsRaw(), candidate.getIngredientsRaw()))
			.usageKo(detail.usage())
			.cautionKo(detail.caution())
			.build();

		// 5) 이미지 호스팅 — 실패해도 초안은 만든다(검수에서 직접 넣을 수 있어야 한다).
		List<String> hosted = hostImages(limit(detail.images()), notes);

		// 6) 상세 HTML
		String html = detailHtmlBuilder.build(draft, hosted, detail);
		draft.applyEnrichment(html, toJson(hosted), String.join(" · ", notes));

		// 7) 마켓별 초안
		String brandKo = text.brandKo() != null ? text.brandKo()
			: SearchKeywordDeriver.extractKoreanBrand(candidate.getNameKo());
		for (MarketType market : TARGET_MARKETS) {
			MarketDraft md = marketDraftBuilder.build(draft, market, brandKo, text.keywords(),
				text.categoryHint(), candidate.getCategorySlug(), config.getCouponRate());
			draft.putMarketDraft(md);
		}

		// 8) 통관 REVIEW는 사용자 승인 전까지 등록을 막는다.
		if (candidate.getCustomsVerdict() == CustomsVerdict.REVIEW)
			draft.acknowledgeCustoms(false);
		else
			draft.acknowledgeCustoms(true);

		return draftPersistTxService.saveAndMarkDrafted(draft, candidate.getId());
	}

	// --- 보조 ---

	private List<String> hostImages(List<String> sourceImages, List<String> notes) {
		if (sourceImages.isEmpty()) {
			notes.add("원본 이미지를 찾지 못함");
			return List.of();
		}
		try {
			List<ImageUploadFile> files = imageDownloadClient.downloadAndConvert(sourceImages);
			Map<String, String> uploaded = imageStorageClient.uploadImages(files);
			return new ArrayList<>(uploaded.values());
		} catch (Exception e) {
			// 조용히 원본 URL로 넘기지 않는다 — 마켓이 외부 URL을 거부하거나 나중에 깨진다.
			log.warn("[초안생성] 이미지 호스팅 실패: {}", e.getMessage());
			notes.add("이미지 호스팅 실패(검수에서 재시도 필요): " + e.getMessage());
			return List.of();
		}
	}

	private List<String> limit(List<String> images) {
		if (images == null)
			return List.of();
		return images.size() <= MAX_IMAGES ? images : images.subList(0, MAX_IMAGES);
	}

	private BigDecimal capacityOf(ProductDetailDto detail) {
		return detail.packageQuantity() != null
			? BigDecimal.valueOf(detail.packageQuantity()) : BigDecimal.ONE;
	}

	private String resolveOrigin(ProductDetailDto detail) {
		// iHerb 상세는 제조국을 일관되게 주지 않는다. 확인 못 하면 "상세설명 참조"로 두고
		// 검수 화면에서 사용자가 채우게 한다(임의로 "미국"이라 적으면 원산지 허위표기가 된다).
		return "상세설명 참조";
	}

	private String hsCodeFor(String categorySlug) {
		// 보충제류 HS 코드. 기존 Product.determineHsCode와 같은 값.
		return "grocery".equalsIgnoreCase(categorySlug) ? "" : "2106.90.9099";
	}

	private String firstNonBlank(String a, String b) {
		return a != null && !a.isBlank() ? a : b;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			return "[]";
		}
	}

	public record Failure(Long candidateId, String name, String reason) {
	}

	public record Result(List<ProductDraft> drafts, List<Failure> failures) {
	}
}

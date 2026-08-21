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

@Slf4j
@Service
@RequiredArgsConstructor
public class DraftEnrichmentUseCase {
	private static final List<MarketType> TARGET_MARKETS = List.of(
		MarketType.COUPANG, MarketType.SMART_STORE, MarketType.ELEVEN_STREET, MarketType.CAFE24);

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

		ProductDetailDto detail = detailCrawler.fetchDetail(candidate.getSourceUrl());
		if (!detail.ok())
			notes.add("상세 크롤 불완전: " + detail.error());

		BigDecimal unitCost = candidate.effectiveBuyPrice();
		Double weightG = detail.shippingWeightGrams() != null
			? detail.shippingWeightGrams().doubleValue() : null;
		BundleQuantityOptimizer.Recommendation bundle = bundleOptimizer.recommend(unitCost, weightG);
		notes.add("묶음 추천: " + bundle.reason());

		ProductTextService.Result text = productTextService.generate(
			candidate, detail.mainIngredients(), detail.packageQuantity(),
			MeasureUnit.EA.getDescription());
		if ("rule-based".equals(text.source()))
			notes.add("상품명·키워드를 규칙 기반으로 생성(LLM 미사용/실패)");

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

		List<String> hosted = hostImages(limit(detail.images()), notes);

		String html = detailHtmlBuilder.build(draft, hosted, detail);
		draft.applyEnrichment(html, toJson(hosted), String.join(" · ", notes));

		String brandKo = text.brandKo() != null ? text.brandKo()
			: SearchKeywordDeriver.extractKoreanBrand(candidate.getNameKo());
		for (MarketType market : TARGET_MARKETS) {
			MarketDraft md = marketDraftBuilder.build(draft, market, brandKo, text.keywords(),
				text.categoryHint(), candidate.getCategorySlug(), config.getCouponRate());
			draft.putMarketDraft(md);
		}

		if (candidate.getCustomsVerdict() == CustomsVerdict.REVIEW)
			draft.acknowledgeCustoms(false);
		else
			draft.acknowledgeCustoms(true);

		return draftPersistTxService.saveAndMarkDrafted(draft, candidate.getId());
	}

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
		return "상세설명 참조";
	}

	private String hsCodeFor(String categorySlug) {
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

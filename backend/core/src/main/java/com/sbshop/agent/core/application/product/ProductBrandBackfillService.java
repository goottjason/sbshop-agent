package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.application.product.port.BrandLookupOutcome;
import com.sbshop.agent.core.application.product.port.CoupangBrandLookupPort;
import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.application.sourcing.port.ProductDetailCrawlerPort;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductBrandBackfillService {
	private static final long DEFAULT_THROTTLE_MS = 300L;
	private static final long HTTP_VENDOR_THROTTLE_MS = 1500L;

	private final ProductReader productReader;
	private final ProductWriter productWriter;
	private final ProductRepository productRepository;
	private final ProductDetailCrawlerPort productDetailCrawlerPort;
	private final CoupangBrandLookupPort coupangBrandLookupPort;
	private final ProcessStatusService processStatusService;
	private final ApplicationEventPublisher eventPublisher;

	public List<Long> findTargets(VendorType vendor, int limit) {
		List<Long> ids = vendor == null
			? productRepository.findBrandBackfillTargetIds()
			: productRepository.findBrandBackfillTargetIds(vendor);
		if (limit > 0 && ids.size() > limit) {
			return ids.subList(0, limit);
		}
		return ids;
	}

	@Async("productBatchExecutor")
	public void backfillBrands(String batchId, List<Long> productIds, String actionType) {
		int updated = 0;
		int skipped = 0;
		int failed = 0;
		for (Long productId : productIds) {
			String code = String.valueOf(productId);
			try {
				Product product = productReader.findById(productId)
					.orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));

				String sourceUrl = product.getSourcingUrl();
				if (sourceUrl == null || sourceUrl.isBlank()) {
					processStatusService.markFailed(batchId, code,
						"[%s] 소싱 URL 없음".formatted(product.getSbCode()));
					failed++;
					continue;
				}

				ProductDetailDto detail = productDetailCrawlerPort.fetchDetail(sourceUrl);
				BrandCandidates candidates = parseBrandKo(detail.brandKo());

				if (candidates == null) {
					processStatusService.markSuccess(batchId, code,
						"[%s] 크롤이 브랜드를 주지 않음 → 건너뜀".formatted(product.getSbCode()));
					skipped++;
				} else {
					BrandResolution resolution = resolveOfficialBrand(candidates);
					if (resolution.lookupFailed()) {
						processStatusService.markSuccess(batchId, code,
							"[%s] 쿠팡 브랜드 조회 실패 → 기존 값 유지(건너뜀)".formatted(product.getSbCode()));
						skipped++;
					} else if (resolution.brand().equals(firstWordOf(product.getOriginalName()))) {
						processStatusService.markSuccess(batchId, code,
							"[%s] 크롤 결과가 기존과 동일(%s) → 건너뜀".formatted(product.getSbCode(), resolution.brand()));
						skipped++;
					} else {
						product.update(ProductUpdateCommand.builder().brand(resolution.brand()).build());
						productWriter.save(product);
						String source = resolution.coupangMatched()
							? "쿠팡 1위 · 후보=" + resolution.candidates()
							: "쿠팡 미등록(크롤값)";
						processStatusService.markSuccess(batchId, code,
							"[%s] 브랜드 %s ← %s".formatted(product.getSbCode(), resolution.brand(), source));
						updated++;
					}
				}
				throttle(product.getVendor());
			} catch (Exception e) {
				log.error("[브랜드백필] 실패 productId={}", productId, e);
				processStatusService.markFailed(batchId, code, e.getMessage());
				failed++;
			}
		}
		String message = "브랜드 백필 완료 (수집 %d / 건너뜀 %d / 실패 %d)".formatted(updated, skipped, failed);
		log.info("[브랜드백필] batchId={} {}", batchId, message);
		eventPublisher.publishEvent(
			new BatchCompletedEvent(this, batchId, actionType, failed == 0, message));
	}

	private record BrandCandidates(String english, String korean, String fallback) {
	}

	private record BrandResolution(String brand, boolean coupangMatched, boolean lookupFailed,
		java.util.List<String> candidates) {
	}

	private BrandResolution resolveOfficialBrand(BrandCandidates candidates) {
		BrandLookupOutcome english = candidates.english() != null
			? coupangBrandLookupPort.findOfficialBrandName(candidates.english())
			: BrandLookupOutcome.notRegistered();
		BrandLookupOutcome korean = candidates.korean() != null
			? coupangBrandLookupPort.findOfficialBrandName(candidates.korean())
			: BrandLookupOutcome.notRegistered();
		if (korean.isMatched()) {
			return new BrandResolution(korean.officialBrandName(), true, false, korean.candidates());
		}
		if (english.isMatched()) {
			return new BrandResolution(english.officialBrandName(), true, false, english.candidates());
		}
		boolean lookupFailed = !korean.isCacheable() || !english.isCacheable();
		return new BrandResolution(candidates.fallback(), false, lookupFailed, java.util.List.of());
	}

	private static BrandCandidates parseBrandKo(String brandKo) {
		if (brandKo == null || brandKo.isBlank()) {
			return null;
		}
		int open = brandKo.lastIndexOf('(');
		int close = brandKo.lastIndexOf(')');
		if (open >= 0 && close > open) {
			String english = nullIfBlank(brandKo.substring(0, open).trim());
			String korean = nullIfBlank(collapseSpaces(brandKo.substring(open + 1, close)));
			String fallback = korean != null ? korean : english;
			return fallback == null ? null : new BrandCandidates(english, korean, fallback);
		}
		String whole = collapseSpaces(brandKo);
		return whole.isBlank() ? null : new BrandCandidates(brandKo.trim(), null, whole);
	}

	private static String collapseSpaces(String s) {
		return s.replaceAll("\\s+", "").trim();
	}

	private static String nullIfBlank(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}

	private static String firstWordOf(String originalName) {
		if (originalName == null) {
			return null;
		}
		int spaceIdx = originalName.indexOf(' ');
		return spaceIdx >= 0 ? originalName.substring(0, spaceIdx) : originalName;
	}

	private void throttle(VendorType vendor) throws InterruptedException {
		Thread.sleep(vendor == VendorType.VTB ? HTTP_VENDOR_THROTTLE_MS : DEFAULT_THROTTLE_MS);
	}
}

package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
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
				String extractedBrand = extractBrand(detail.brandKo());

				if (extractedBrand == null) {
					processStatusService.markSuccess(batchId, code,
						"[%s] 크롤이 브랜드를 주지 않음 → 건너뜀".formatted(product.getSbCode()));
					skipped++;
				} else if (extractedBrand.equals(firstWordOf(product.getOriginalName()))) {
					processStatusService.markSuccess(batchId, code,
						"[%s] 크롤 결과가 기존과 동일(%s) → 건너뜀".formatted(product.getSbCode(), extractedBrand));
					skipped++;
				} else {
					product.update(ProductUpdateCommand.builder().brand(extractedBrand).build());
					productWriter.save(product);
					processStatusService.markSuccess(batchId, code,
						"[%s] 브랜드 수집 %s".formatted(product.getSbCode(), extractedBrand));
					updated++;
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

	private static String extractBrand(String brandKo) {
		if (brandKo == null || brandKo.isBlank()) {
			return null;
		}
		int parenIdx = brandKo.indexOf(" (");
		String extracted = (parenIdx >= 0 ? brandKo.substring(0, parenIdx) : brandKo).trim();
		return extracted.isBlank() ? null : extracted;
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

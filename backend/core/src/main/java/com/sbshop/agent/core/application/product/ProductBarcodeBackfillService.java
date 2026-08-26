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
import com.sbshop.agent.core.domain.product.service.BarcodeValidator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductBarcodeBackfillService {
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
			? productRepository.findBarcodeBackfillTargetIds()
			: productRepository.findBarcodeBackfillTargetIds(vendor);
		if (limit > 0 && ids.size() > limit) {
			return ids.subList(0, limit);
		}
		return ids;
	}

	@Async("productBatchExecutor")
	public void backfillBarcodes(String batchId, List<Long> productIds, String actionType) {
		int filled = 0;
		int skipped = 0;
		int failed = 0;
		for (Long productId : productIds) {
			String code = String.valueOf(productId);
			try {
				Product product = productReader.findById(productId)
					.orElseThrow(() -> new IllegalArgumentException("상품 없음: " + productId));

				if (hasBarcode(product)) {
					processStatusService.markSuccess(batchId, code,
						"[%s] 이미 바코드 보유 → 건너뜀".formatted(product.getSbCode()));
					skipped++;
					continue;
				}

				String sourceUrl = product.getSourcingUrl();
				if (sourceUrl == null || sourceUrl.isBlank()) {
					processStatusService.markFailed(batchId, code,
						"[%s] 소싱 URL 없음".formatted(product.getSbCode()));
					failed++;
					continue;
				}

				ProductDetailDto detail = productDetailCrawlerPort.fetchDetail(sourceUrl);
				BarcodeValidator.Result checked = BarcodeValidator.validate(detail.upc());

				if (checked.valid()) {
					product.update(ProductUpdateCommand.builder().barcode(checked.normalized()).build());
					productWriter.save(product);
					processStatusService.markSuccess(batchId, code,
						"[%s] 바코드 수집 %s".formatted(product.getSbCode(), checked.normalized()));
					filled++;
				} else if (checked.absent() && !detail.ok()) {
					processStatusService.markFailed(batchId, code,
						"[%s] 크롤 실패: %s".formatted(product.getSbCode(), reasonOf(detail)));
					failed++;
				} else {
					processStatusService.markFailed(batchId, code,
						"[%s] %s".formatted(product.getSbCode(), checked.reason()));
					failed++;
				}
				throttle(product.getVendor());
			} catch (Exception e) {
				log.error("[바코드백필] 실패 productId={}", productId, e);
				processStatusService.markFailed(batchId, code, e.getMessage());
				failed++;
			}
		}
		String message = "바코드 백필 완료 (수집 %d / 건너뜀 %d / 실패 %d)".formatted(filled, skipped, failed);
		log.info("[바코드백필] batchId={} {}", batchId, message);
		eventPublisher.publishEvent(
			new BatchCompletedEvent(this, batchId, actionType, failed == 0, message));
	}

	private static boolean hasBarcode(Product product) {
		return product.getProductSpec() != null
			&& product.getProductSpec().getBarcode() != null
			&& !product.getProductSpec().getBarcode().isBlank();
	}

	private static String reasonOf(ProductDetailDto detail) {
		if (detail.error() != null && !detail.error().isBlank()) {
			return detail.error();
		}
		return detail.status() != null ? detail.status() : "사유 미상";
	}

	private void throttle(VendorType vendor) throws InterruptedException {
		Thread.sleep(vendor == VendorType.VTB ? HTTP_VENDOR_THROTTLE_MS : DEFAULT_THROTTLE_MS);
	}
}

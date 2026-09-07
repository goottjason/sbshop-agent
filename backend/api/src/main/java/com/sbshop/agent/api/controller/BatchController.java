package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.batch.BarcodeBackfillRequest;
import com.sbshop.agent.api.dto.batch.BrandBackfillRequest;
import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateAllRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateRequest;
import com.sbshop.agent.api.dto.batch.ProcessStatusResponse;
import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.BatchSummary;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.ProductBarcodeBackfillService;
import com.sbshop.agent.core.application.product.ProductBrandBackfillService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.application.product.event.BatchStartedEvent;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/products/batch")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BatchController {
	private final BatchPriceStockService batchPriceStockService;
	private final ProcessStatusService processStatusService;
	private final ActionLogService actionLogService;
	private final ApplicationEventPublisher eventPublisher;
	private final ProductBarcodeBackfillService productBarcodeBackfillService;
	private final ProductBrandBackfillService productBrandBackfillService;

	@PostMapping("/crawl-and-update")
	public ResponseEntity<Map<String, String>> crawlAndUpdate(@RequestBody
	CrawlAndUpdateRequest request) {
		if (request.productIds() == null || request.productIds().isEmpty()) {
			throw new IllegalArgumentException("productIds는 필수이며 비어 있을 수 없습니다.");
		}
		return sourceReviewRequired();
	}

	private ResponseEntity<Map<String, String>> sourceReviewRequired() {
		return ResponseEntity.status(409).body(Map.of("code", "SOURCE_REVIEW_REQUIRED",
			"message", "소싱 가격·재고는 수집 내용을 검토한 뒤 저장해야 합니다. 상품관리 또는 배치 화면의 소싱 갱신 검토를 사용하세요.",
			"reviewPath", "/api/v1/products/source-refresh/collections"));
	}

	@PostMapping("/manual-update-price-stock")
	public ResponseEntity<Map<String, String>> manualUpdate(@RequestBody
	ManualUpdateRequest request) {
		List<PriceStockItem> items = request.items() != null ? request.items() : new ArrayList<>();
		List<String> productCodes = items.stream()
			.map(item -> String.valueOf(item.productId()))
			.toList();
		String batchId = startBatchWithLog(
			JobType.MANUAL_UPDATE_PRICE_STOCK,
			productCodes, ActionLogConstants.BATCH_MANUAL_UPDATE,
			id -> "수동 일괄 업데이트 시작 (batchId=" + id + ", " + productCodes.size() + "건)");
		batchPriceStockService.manualUpdatePriceStock(batchId, items);
		return ResponseEntity.ok(Map.of(
			"batchId", batchId,
			"count", String.valueOf(productCodes.size()),
			"message", "수동 일괄 업데이트가 시작되었습니다."));
	}

	@PostMapping("/manual-update-all")
	public ResponseEntity<Map<String, String>> manualUpdateAll(@RequestBody
	ManualUpdateAllRequest request) {
		if (request.productIds() == null || request.commands() == null
			|| request.productIds().size() != request.commands().size()) {
			throw new IllegalArgumentException(
				"productIds와 commands의 개수가 일치해야 합니다 (productIds="
					+ (request.productIds() == null ? "null" : request.productIds().size())
					+ ", commands=" + (request.commands() == null ? "null" : request.commands().size()) + ")");
		}
		List<String> productCodes = request.productIds().stream()
			.map(String::valueOf)
			.toList();
		String batchId = startBatchWithLog(
			JobType.MANUAL_UPDATE_ALL_FIELDS,
			productCodes, ActionLogConstants.BATCH_MANUAL_UPDATE_ALL,
			id -> "전체필드 일괄 업데이트 시작 (batchId=" + id + ", " + productCodes.size() + "건)");
		batchPriceStockService.manualUpdateAllFields(batchId, request.productIds(), request.commands());
		return ResponseEntity.ok(Map.of(
			"batchId", batchId,
			"count", String.valueOf(productCodes.size()),
			"message", "전체 필드 일괄 업데이트가 시작되었습니다."));
	}

	@PostMapping("/by-supplier")
	public ResponseEntity<Map<String, String>> updateBySupplier(@RequestBody
	SupplierBatchRequest request) {
		if (request.supplierCode() == null || request.supplierCode().isBlank()) {
			throw new IllegalArgumentException("supplierCode는 필수입니다.");
		}
		return sourceReviewRequired();
	}

	@PostMapping("/backfill-barcode")
	public ResponseEntity<Map<String, String>> backfillBarcode(@RequestBody
	BarcodeBackfillRequest request) {
		VendorType vendor = request.supplierCode() == null || request.supplierCode().isBlank()
			? null : VendorType.valueOf(request.supplierCode().toUpperCase());
		int limit = request.limit() != null ? request.limit() : 0;
		List<Long> productIds = productBarcodeBackfillService.findTargets(vendor, limit);
		if (productIds.isEmpty()) {
			return ResponseEntity.ok(Map.of(
				"batchId", "", "count", "0", "message", "바코드 미보유 대상이 없습니다."));
		}
		List<String> productCodes = productIds.stream().map(String::valueOf).toList();
		String batchId = startBatchWithLog(
			JobType.BACKFILL_BARCODE,
			productCodes, ActionLogConstants.BATCH_BACKFILL_BARCODE,
			id -> "바코드 백필 배치 시작 (" + (vendor == null ? "전체" : vendor.name())
				+ ", batchId=" + id + ", " + productCodes.size() + "건)");
		productBarcodeBackfillService.backfillBarcodes(
			batchId, productIds, ActionLogConstants.BATCH_BACKFILL_BARCODE);
		return ResponseEntity.ok(Map.of(
			"batchId", batchId,
			"count", String.valueOf(productIds.size()),
			"message", "바코드 백필 배치가 시작되었습니다."));
	}

	@PostMapping("/backfill-brand")
	public ResponseEntity<Map<String, String>> backfillBrand(@RequestBody
	BrandBackfillRequest request) {
		VendorType vendor = request.supplierCode() == null || request.supplierCode().isBlank()
			? null : VendorType.valueOf(request.supplierCode().toUpperCase());
		int limit = request.limit() != null ? request.limit() : 0;
		List<Long> productIds = productBrandBackfillService.findTargets(vendor, limit);
		if (productIds.isEmpty()) {
			return ResponseEntity.ok(Map.of(
				"batchId", "", "count", "0", "message", "브랜드 백필 대상이 없습니다."));
		}
		List<String> productCodes = productIds.stream().map(String::valueOf).toList();
		String batchId = startBatchWithLog(
			JobType.BACKFILL_BRAND,
			productCodes, ActionLogConstants.BATCH_BACKFILL_BRAND,
			id -> "브랜드 백필 배치 시작 (" + (vendor == null ? "전체" : vendor.name())
				+ ", batchId=" + id + ", " + productCodes.size() + "건)");
		productBrandBackfillService.backfillBrands(
			batchId, productIds, ActionLogConstants.BATCH_BACKFILL_BRAND);
		return ResponseEntity.ok(Map.of(
			"batchId", batchId,
			"count", String.valueOf(productIds.size()),
			"message", "브랜드 백필 배치가 시작되었습니다."));
	}

	@GetMapping("/status")
	public ResponseEntity<List<String>> getAllBatchIds() {
		return ResponseEntity.ok(processStatusService.getAllBatchIds());
	}

	@GetMapping("/status/{batchId}")
	public ResponseEntity<List<ProcessStatusResponse>> getBatchStatus(
		@PathVariable
		String batchId,
		@RequestParam(name = "status", required = false)
		ProcessStatusType status) {
		List<ProcessStatusResponse> statuses = processStatusService.getBatchStatus(batchId, status).stream()
			.map(ProcessStatusResponse::from)
			.toList();
		return ResponseEntity.ok(statuses);
	}

	@GetMapping("/status/{batchId}/summary")
	public ResponseEntity<BatchSummary> getBatchSummary(@PathVariable
	String batchId) {
		return ResponseEntity.ok(processStatusService.getBatchSummary(batchId));
	}

	private String startBatchWithLog(
		JobType jobType,
		List<String> productCodes,
		String actionType,
		Function<String, String> messageBuilder) {
		String batchId = processStatusService.startBatch(jobType, productCodes);
		actionLogService.record(actionType, null, ActionStatus.STARTED, messageBuilder.apply(batchId));
		eventPublisher.publishEvent(new BatchStartedEvent(
			this, batchId, actionType, productCodes.size()));
		return batchId;
	}
}

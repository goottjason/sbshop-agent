package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateAllRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateRequest;
import com.sbshop.agent.api.dto.batch.ProcessStatusResponse;
import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.BatchSummary;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.application.product.event.BatchStartedEvent;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
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

	@PostMapping("/crawl-and-update")
	public ResponseEntity<Map<String, String>> crawlAndUpdate(@RequestBody
	CrawlAndUpdateRequest request) {
		if (request.productIds() == null || request.productIds().isEmpty()) {
			throw new IllegalArgumentException("productIds는 필수이며 비어 있을 수 없습니다.");
		}
		List<String> productCodes = request.productIds().stream()
			.map(String::valueOf)
			.toList();
		String batchId = startBatchWithLog(
			JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
			productCodes, ActionLogConstants.BATCH_CRAWL_UPDATE,
			id -> "배치 크롤 업데이트 시작 (batchId=" + id + ", " + productCodes.size() + "건)");
		batchPriceStockService.crawlAndUpdatePriceStock(
			batchId, request.productIds(),
			request.marginRate() != null ? request.marginRate() : new BigDecimal("15"),
			request.couponRate() != null ? request.couponRate() : new BigDecimal("20"),
			request.minMarginPrice() != null ? request.minMarginPrice() : new BigDecimal("5000"),
			ActionLogConstants.BATCH_CRAWL_UPDATE);
		return ResponseEntity.ok(Map.of(
			"batchId", batchId,
			"count", String.valueOf(productCodes.size()),
			"message", "크롤 기반 일괄 업데이트가 시작되었습니다."));
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
		VendorType vendor = VendorType.valueOf(request.supplierCode().toUpperCase());
		List<Long> productIds = batchPriceStockService.getProductIdsByVendor(vendor);
		if (productIds.isEmpty()) {
			return ResponseEntity.ok(Map.of(
				"batchId", "", "count", "0", "message", "해당 소싱업체의 상품이 없습니다."));
		}
		List<String> productCodes = productIds.stream().map(String::valueOf).toList();
		String batchId = startBatchWithLog(
			JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
			productCodes, ActionLogConstants.BATCH_BY_SUPPLIER,
			id -> "소싱업체별 배치 시작 (" + vendor.name() + ", batchId=" + id
				+ ", " + productCodes.size() + "건)");
		batchPriceStockService.crawlAndUpdatePriceStock(
			batchId, productIds,
			request.marginRate() != null ? request.marginRate() : new BigDecimal("15"),
			request.couponRate() != null ? request.couponRate() : new BigDecimal("20"),
			request.minMarginPrice() != null ? request.minMarginPrice() : new BigDecimal("5000"),
			ActionLogConstants.BATCH_BY_SUPPLIER);
		return ResponseEntity.ok(Map.of(
			"batchId", batchId,
			"count", String.valueOf(productIds.size()),
			"message", "소싱업체별 일괄 업데이트가 시작되었습니다."));
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

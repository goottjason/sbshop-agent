package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateAllRequest;
import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/products/batch")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BatchController {

	private final BatchPriceStockService batchPriceStockService;
	private final ProcessStatusService processStatusService;
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	@PostMapping("/crawl-and-update")
	public ResponseEntity<Map<String, String>> crawlAndUpdate(@RequestBody
	CrawlAndUpdateRequest request) {
		List<String> productCodes = request.productIds().stream()
			.map(String::valueOf)
			.toList();
		String batchId = processStatusService.startBatch(
			com.sbshop.agent.core.domain.process.enums.JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
			productCodes);
		// D-076: 배치 크롤 업데이트(비동기·batchId 연동) — 시작 기록(STARTED). 완료는 배치 진행현황(batchId) 추적.
		actionLogService.record(ActionLogConstants.BATCH_CRAWL_UPDATE, null,
			ActionStatus.STARTED, "배치 크롤 업데이트 시작 (batchId=" + batchId + ", " + productCodes.size() + "건)");
		batchPriceStockService.crawlAndUpdatePriceStock(
			batchId, request.productIds(),
			request.marginRate() != null ? request.marginRate() : new BigDecimal("15"),
			request.couponRate() != null ? request.couponRate() : new BigDecimal("20"),
			request.minMarginPrice() != null ? request.minMarginPrice() : new BigDecimal("5000"),
			ActionLogConstants.BATCH_CRAWL_UPDATE);
		return ResponseEntity.ok(Map.of("batchId", batchId, "message", "크롤 기반 일괄 업데이트가 시작되었습니다."));
	}

	@PostMapping("/manual-update-price-stock")
	public ResponseEntity<Map<String, String>> manualUpdate(@RequestBody
	ManualUpdateRequest request) {
		// F-BATCH-M1: productId·price·stock을 쌍(items)으로 받아 각 값이 자기 상품에 묶여 전달된다.
		List<PriceStockItem> items = request.items() != null ? request.items() : new ArrayList<>();
		List<String> productCodes = items.stream()
			.map(item -> String.valueOf(item.productId()))
			.toList();
		String batchId = processStatusService.startBatch(
			com.sbshop.agent.core.domain.process.enums.JobType.MANUAL_UPDATE_PRICE_STOCK,
			productCodes);
		// D-076: 수동 일괄 업데이트 — 시작 기록.
		actionLogService.record(ActionLogConstants.BATCH_MANUAL_UPDATE, null,
			ActionStatus.STARTED, "수동 일괄 업데이트 시작 (batchId=" + batchId + ", " + productCodes.size() + "건)");
		batchPriceStockService.manualUpdatePriceStock(batchId, items);
		return ResponseEntity.ok(Map.of("batchId", batchId, "message", "수동 일괄 업데이트가 시작되었습니다."));
	}

	@PostMapping("/manual-update-all")
	public ResponseEntity<Map<String, String>> manualUpdateAll(@RequestBody
	ManualUpdateAllRequest request) {
		// F-BATCH-A2/SP-3: commands는 productIds와 index 위치로 매핑되므로 길이가 일치해야 한다.
		// 불일치 시 서비스에서 IndexOutOfBounds(500)가 나므로, 진입부에서 400으로 명확히 거부한다.
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
		String batchId = processStatusService.startBatch(
			com.sbshop.agent.core.domain.process.enums.JobType.MANUAL_UPDATE_ALL_FIELDS,
			productCodes);
		// D-076: 전체필드 일괄 업데이트 — 시작 기록.
		actionLogService.record(ActionLogConstants.BATCH_MANUAL_UPDATE_ALL, null,
			ActionStatus.STARTED, "전체필드 일괄 업데이트 시작 (batchId=" + batchId + ", " + productCodes.size() + "건)");
		batchPriceStockService.manualUpdateAllFields(batchId, request.productIds(), request.commands());
		return ResponseEntity.ok(Map.of("batchId", batchId, "message", "전체 필드 일괄 업데이트가 시작되었습니다."));
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
			return ResponseEntity.ok(Map.of("message", "해당 소싱업체의 상품이 없습니다."));
		}
		List<String> productCodes = productIds.stream().map(String::valueOf).toList();
		String batchId = processStatusService.startBatch(
			com.sbshop.agent.core.domain.process.enums.JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
			productCodes);
		// D-076: 소싱업체별 배치 — 시작 기록.
		actionLogService.record(ActionLogConstants.BATCH_BY_SUPPLIER, null,
			ActionStatus.STARTED, "소싱업체별 배치 시작 (" + vendor.name() + ", batchId=" + batchId
				+ ", " + productCodes.size() + "건)");
		batchPriceStockService.crawlAndUpdatePriceStock(
			batchId, productIds,
			request.marginRate() != null ? request.marginRate() : new BigDecimal("15"),
			request.couponRate() != null ? request.couponRate() : new BigDecimal("20"),
			request.minMarginPrice() != null ? request.minMarginPrice() : new BigDecimal("5000"),
			ActionLogConstants.BATCH_BY_SUPPLIER);
		return ResponseEntity.ok(Map.of("batchId", batchId, "count", String.valueOf(productIds.size())));
	}

	@GetMapping("/status/{batchId}")
	public ResponseEntity<List<ProcessStatus>> getBatchStatus(@PathVariable
	String batchId) {
		return ResponseEntity.ok(processStatusService.getBatchStatus(batchId));
	}

	// 폴링용 경량 집계(전체 행 대신 count 쿼리). /status/{batchId}는 상세 조회용으로 유지.
	@GetMapping("/status/{batchId}/summary")
	public ResponseEntity<com.sbshop.agent.core.application.process.BatchSummary> getBatchSummary(@PathVariable
	String batchId) {
		return ResponseEntity.ok(processStatusService.getBatchSummary(batchId));
	}

	@GetMapping("/status")
	public ResponseEntity<List<String>> getAllBatchIds() {
		return ResponseEntity.ok(processStatusService.getAllBatchIds());
	}
}

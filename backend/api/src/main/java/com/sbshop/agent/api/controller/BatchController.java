package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.batch.CrawlAndUpdateRequest;
import com.sbshop.agent.api.dto.batch.ManualUpdateRequest;
import com.sbshop.agent.api.dto.batch.SupplierBatchRequest;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.BatchPriceStockService;
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

	@PostMapping("/crawl-and-update")
	public ResponseEntity<Map<String, String>> crawlAndUpdate(@RequestBody CrawlAndUpdateRequest request) {
		List<String> productCodes = request.productIds().stream()
				.map(String::valueOf)
				.toList();
		String batchId = processStatusService.startBatch(
				com.sbshop.agent.core.domain.process.enums.JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
				productCodes);
		batchPriceStockService.crawlAndUpdatePriceStock(
				batchId, request.productIds(),
				request.marginRate() != null ? request.marginRate() : new BigDecimal("15"),
				request.couponRate() != null ? request.couponRate() : new BigDecimal("20"),
				request.minMarginPrice() != null ? request.minMarginPrice() : new BigDecimal("5000"));
		return ResponseEntity.ok(Map.of("batchId", batchId, "message", "크롤 기반 일괄 업데이트가 시작되었습니다."));
	}

	@PostMapping("/manual-update-price-stock")
	public ResponseEntity<Map<String, String>> manualUpdate(@RequestBody ManualUpdateRequest request) {
		List<String> productCodes = request.productIds().stream()
				.map(String::valueOf)
				.toList();
		String batchId = processStatusService.startBatch(
				com.sbshop.agent.core.domain.process.enums.JobType.MANUAL_UPDATE_PRICE_STOCK,
				productCodes);
		batchPriceStockService.manualUpdatePriceStock(
				batchId, request.productIds(),
				request.prices() != null ? request.prices() : new ArrayList<>(),
				request.stocks() != null ? request.stocks() : new ArrayList<>());
		return ResponseEntity.ok(Map.of("batchId", batchId, "message", "수동 일괄 업데이트가 시작되었습니다."));
	}

	@PostMapping("/by-supplier")
	public ResponseEntity<Map<String, String>> updateBySupplier(@RequestBody SupplierBatchRequest request) {
		VendorType vendor = VendorType.valueOf(request.supplierCode().toUpperCase());
		List<Long> productIds = batchPriceStockService.getProductIdsByVendor(vendor);
		if (productIds.isEmpty()) {
			return ResponseEntity.ok(Map.of("message", "해당 소싱업체의 상품이 없습니다."));
		}
		List<String> productCodes = productIds.stream().map(String::valueOf).toList();
		String batchId = processStatusService.startBatch(
				com.sbshop.agent.core.domain.process.enums.JobType.CRAWL_AND_UPDATE_PRICE_STOCK,
				productCodes);
		batchPriceStockService.crawlAndUpdatePriceStock(
				batchId, productIds,
				request.marginRate() != null ? request.marginRate() : new BigDecimal("15"),
				request.couponRate() != null ? request.couponRate() : new BigDecimal("20"),
				request.minMarginPrice() != null ? request.minMarginPrice() : new BigDecimal("5000"));
		return ResponseEntity.ok(Map.of("batchId", batchId, "count", String.valueOf(productIds.size())));
	}

	@GetMapping("/status/{batchId}")
	public ResponseEntity<List<ProcessStatus>> getBatchStatus(@PathVariable String batchId) {
		return ResponseEntity.ok(processStatusService.getBatchStatus(batchId));
	}
}

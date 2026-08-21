package com.sbshop.agent.worker.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sbshop.agent.core.application.product.SmartstoreSellerDiscountRemovalService;
import com.sbshop.agent.core.config.InternalAccessGuard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/internal/smartstore")
@RequiredArgsConstructor
public class SmartstoreDiscountController {

	private final SmartstoreSellerDiscountRemovalService discountRemovalService;
	private final InternalAccessGuard internalAccessGuard;

	private final AtomicBoolean running = new AtomicBoolean(false);

	@PostMapping("/remove-seller-discount")
	public ResponseEntity<Map<String, Object>> removeSellerDiscount(
		@RequestHeader(value = InternalAccessGuard.HEADER_NAME, required = false)
		String internalToken,
		@RequestParam(value = "productIds", required = false)
		List<Long> productIds,
		@RequestParam(value = "dryRun", defaultValue = "true")
		boolean dryRun) {
		if (!internalAccessGuard.isAllowed(internalToken)) {
			log.warn("[내부트리거] 스토어 즉시할인 제거 접근 거부 — 유효한 내부 토큰 없음");
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("ok", false, "message", "forbidden: invalid internal token"));
		}

		if (productIds != null && !productIds.isEmpty()) {
			log.info("[내부트리거] 스토어 즉시할인 제거(동기) productIds={}, dryRun={}", productIds, dryRun);
			Map<String, Object> summary = discountRemovalService.removeForProducts(productIds, dryRun);
			return ResponseEntity.ok(Map.of("ok", true, "mode", "sync", "summary", summary));
		}

		if (!running.compareAndSet(false, true)) {
			return ResponseEntity.ok(Map.of("ok", true, "started", false, "message", "already running"));
		}
		log.info("[내부트리거] 스토어 즉시할인 제거(전체·비동기) dryRun={}", dryRun);
		discountRemovalService.removeAllAsync(dryRun, () -> running.set(false));
		return ResponseEntity.ok(Map.of("ok", true, "started", true,
			"message", "remove-seller-discount started in background; check logs for progress"));
	}
}

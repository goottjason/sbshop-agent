package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.ProductBarcodeSyncUseCase;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products/barcode")
@RequiredArgsConstructor
public class ProductBarcodeSyncController {

	private final ProductBarcodeSyncUseCase productBarcodeSyncUseCase;

	@PostMapping("/sync")
	public ResponseEntity<Map<String, Object>> sync(@RequestBody
	List<Long> productIds,
		@RequestParam(defaultValue = "true")
		boolean dryRun,
		@RequestParam(defaultValue = "0")
		long throttleMs) {
		if (productIds == null || productIds.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("success", false,
				"message", "productIds 는 명시 대상 전용이다 — 비어 있을 수 없다"));
		}
		return ResponseEntity.ok(Map.of("success", true, "dryRun", dryRun,
			"throttleMs", throttleMs,
			"results", productBarcodeSyncUseCase.sync(productIds, dryRun, throttleMs)));
	}
}

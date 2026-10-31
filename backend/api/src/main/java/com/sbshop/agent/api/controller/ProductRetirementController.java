package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.retirement.ProductRetirementService;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.CancelResult;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.Candidate;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.EsmConfirmation;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.EsmTarget;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.RetirementReport;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.StartResult;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.Summary;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/retirement")
@RequiredArgsConstructor
public class ProductRetirementController {

	private final ProductRetirementService productRetirementService;

	@GetMapping("/candidates")
	public ResponseEntity<Map<String, Object>> candidates(@RequestParam(defaultValue = "IHB")
	String vendor,
		@RequestParam(defaultValue = "100")
		int limit) {
		List<Candidate> results = productRetirementService.candidates(VendorType.valueOf(vendor.toUpperCase()), limit);
		return ResponseEntity.ok(Map.of("success", true, "count", results.size(), "results", results));
	}

	@PostMapping("/start")
	public ResponseEntity<Map<String, Object>> start(@RequestParam(defaultValue = "true")
	boolean dryRun,
		@RequestBody
		List<Long> productIds) {
		List<StartResult> results = productRetirementService.start(productIds, dryRun);
		return ResponseEntity.ok(Map.of("success", true, "dryRun", dryRun, "results", results));
	}

	@PostMapping("/advance")
	public ResponseEntity<Map<String, Object>> advance(@RequestParam(defaultValue = "20")
	int limit,
		@RequestParam(defaultValue = "1200")
		long throttleMs,
		@RequestParam(defaultValue = "true")
		boolean dryRun) {
		try {
			List<RetirementReport> results = productRetirementService.advance(limit, throttleMs, dryRun);
			return ResponseEntity.ok(Map.of("success", true, "dryRun", dryRun, "count", results.size(),
				"results", results));
		} catch (ProductRetirementService.RetirementAlreadyRunningException running) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("success", false, "message", running.getMessage()));
		}
	}

	@GetMapping("/esm-targets")
	public ResponseEntity<Map<String, Object>> esmTargets() {
		List<EsmTarget> results = productRetirementService.esmTargets();
		return ResponseEntity.ok(Map.of("success", true, "count", results.size(), "results", results));
	}

	@PostMapping("/esm-confirm")
	public ResponseEntity<EsmConfirmation> esmConfirm(@RequestBody
	List<String> sbCodes) {
		return ResponseEntity.ok(productRetirementService.confirmEsmAbsent(sbCodes));
	}

	@PostMapping("/cancel")
	public ResponseEntity<Map<String, Object>> cancel(@RequestBody
	List<Long> productIds) {
		List<CancelResult> results = productRetirementService.cancel(productIds);
		return ResponseEntity.ok(Map.of("success", true, "results", results));
	}

	@GetMapping("/summary")
	public ResponseEntity<Summary> summary() {
		return ResponseEntity.ok(productRetirementService.summary());
	}
}

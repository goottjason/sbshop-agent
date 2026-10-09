package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.CoupangListingRepairUseCase;
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
@RequestMapping("/internal/coupang")
@RequiredArgsConstructor
public class CoupangListingRepairController {

	private final CoupangListingRepairUseCase coupangListingRepairUseCase;

	public record Request(List<String> sellerProductIds, String brand) {
	}

	@PostMapping("/listing-repair")
	public ResponseEntity<Map<String, Object>> repair(@RequestBody(required = false)
	Request request,
		@RequestParam(defaultValue = "true")
		boolean dryRun,
		@RequestParam(defaultValue = "500")
		long throttleMs) {
		CoupangListingRepairUseCase.Command command = new CoupangListingRepairUseCase.Command(
			request == null ? null : request.sellerProductIds(), request == null ? null : request.brand(), dryRun,
			throttleMs);
		if (!command.hasTargets()) {
			return ResponseEntity.badRequest().body(Map.of("success", false,
				"message", "sellerProductIds 또는 brand 가 필요하다"));
		}
		return ResponseEntity.ok(Map.of("success", true, "dryRun", dryRun, "throttleMs", throttleMs,
			"results", coupangListingRepairUseCase.repair(command)));
	}
}

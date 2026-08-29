package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.MarketRegistrationProbeService;
import com.sbshop.agent.core.application.product.MarketRegistrationProbeService.ProbeOutcome;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/registrations")
@RequiredArgsConstructor
public class MarketRegistrationProbeController {

	private final MarketRegistrationProbeService marketRegistrationProbeService;

	@PostMapping("/probe")
	public ResponseEntity<Map<String, Object>> probe(@RequestParam
	String market,
		@RequestParam(defaultValue = "20")
		int limit,
		@RequestParam(defaultValue = "300")
		long throttleMs,
		@RequestParam(defaultValue = "true")
		boolean dryRun,
		@RequestParam(defaultValue = "false")
		boolean promoteAlive) {
		MarketType marketType = MarketType.valueOf(market.toUpperCase());
		List<ProbeOutcome> results = marketRegistrationProbeService.probe(marketType, limit, throttleMs, dryRun,
			promoteAlive);
		Map<String, Integer> summary = new LinkedHashMap<>();
		for (ProbeOutcome outcome : results) {
			summary.merge(outcome.result(), 1, Integer::sum);
		}
		return ResponseEntity.ok(Map.of("success", true, "market", marketType.name(), "dryRun", dryRun,
			"summary", summary, "results", results));
	}
}

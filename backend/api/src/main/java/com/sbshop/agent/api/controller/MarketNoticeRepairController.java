package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.MarketNoticeRepairUseCase;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products/notice")
@RequiredArgsConstructor
public class MarketNoticeRepairController {

	private final MarketNoticeRepairUseCase marketNoticeRepairUseCase;

	@PostMapping("/repair")
	public ResponseEntity<Map<String, Object>> repair(
		@RequestParam(defaultValue = "SMART_STORE")
		MarketType market,
		@RequestParam(defaultValue = "0")
		int limit,
		@RequestParam(defaultValue = "250")
		long throttleMs,
		@RequestParam(defaultValue = "true")
		boolean dryRun,
		@RequestParam(defaultValue = "false")
		boolean syncedOnly) {
		return ResponseEntity.ok(Map.of("success", true, "dryRun", dryRun,
			"report", marketNoticeRepairUseCase.repair(market, limit, throttleMs, dryRun, syncedOnly)));
	}
}

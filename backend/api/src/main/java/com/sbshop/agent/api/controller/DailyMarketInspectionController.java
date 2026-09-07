package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.inspection.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/connection-inspections/daily")
public class DailyMarketInspectionController {
	private final DailyMarketInspectionService service;

	@GetMapping
	public DailyMarketInspectionService.DailyStatus status() {
		return service.status();
	}

	@GetMapping("/{id}/batches")
	public List<MarketInspectionService.BatchView> batches(@PathVariable
	String id) {
		return service.batches(id);
	}
}

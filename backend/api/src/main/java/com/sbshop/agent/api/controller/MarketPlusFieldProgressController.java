package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.marketplus.MarketPlusFieldProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class MarketPlusFieldProgressController {
	private final MarketPlusFieldProgressService service;

	@GetMapping("/{productId}/marketplus-field-progress")
	public MarketPlusFieldProgressService.Workspace progress(@PathVariable
	Long productId) {
		return service.progress(productId);
	}
}

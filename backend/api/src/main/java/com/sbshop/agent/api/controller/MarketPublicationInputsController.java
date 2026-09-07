package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.sync.MarketPublicationInputsService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class MarketPublicationInputsController {
	private final MarketPublicationInputsService service;

	@GetMapping("/{id}/publication-inputs")
	public MarketPublicationInputsService.Inputs inputs(@PathVariable
	Long id, @RequestParam
	MarketType market,
		@RequestParam(required = false)
		String categoryId) {
		return service.inputs(id, market, categoryId);
	}
}

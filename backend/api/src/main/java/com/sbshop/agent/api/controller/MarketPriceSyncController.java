package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.sync.MarketPriceSyncService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.market.sync.MarketPriceAttempt;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/price-sync")
public class MarketPriceSyncController {
	private final MarketPriceSyncService service;

	public record Preview(List<Long> productIds, Set<MarketType> markets) {
	}

	@PostMapping("/reviews")
	public MarketPriceSyncService.Review preview(@RequestBody
	Preview request, Principal actor) {
		return service.preview(request.productIds(), request.markets(), actor.getName());
	}

	@PostMapping("/reviews/{id}/commit")
	public MarketPriceSyncService.Review commit(@PathVariable
	String id, Principal actor) {
		return service.commit(id, actor.getName());
	}

	@GetMapping("/reviews/{id}")
	public MarketPriceSyncService.Review get(@PathVariable
	String id) {
		return service.get(id);
	}

	@GetMapping("/reviews")
	public List<MarketPriceSyncService.Review> recent() {
		return service.recent();
	}

	@GetMapping("/tasks/{id}/history")
	public List<MarketPriceAttempt> history(@PathVariable
	Long id) {
		return service.history(id);
	}
}

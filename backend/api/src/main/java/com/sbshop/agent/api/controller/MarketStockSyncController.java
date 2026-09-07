package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.sync.MarketStockSyncService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.market.sync.MarketStockAttempt;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/market-stock-sync")
public class MarketStockSyncController {
	private final MarketStockSyncService service;

	public record Preview(List<Long> productIds, Set<MarketType> markets) {
	}

	@PostMapping("/reviews")
	public MarketStockSyncService.Review preview(@RequestBody
	Preview request, Principal actor) {
		return service.preview(request.productIds(), request.markets(), actor.getName());
	}

	@PostMapping("/reviews/{id}/commit")
	public MarketStockSyncService.Review commit(@PathVariable
	String id, Principal actor) {
		return service.commit(id, actor.getName());
	}

	@GetMapping("/reviews/{id}")
	public MarketStockSyncService.Review get(@PathVariable
	String id, Principal actor) {
		return service.get(id, actor.getName());
	}

	@GetMapping("/reviews")
	public List<MarketStockSyncService.Review> recent(Principal actor) {
		return service.recent(actor.getName());
	}

	@GetMapping("/tasks/{id}/history")
	public List<MarketStockAttempt> history(@PathVariable
	Long id, Principal actor) {
		return service.history(id, actor.getName());
	}
}

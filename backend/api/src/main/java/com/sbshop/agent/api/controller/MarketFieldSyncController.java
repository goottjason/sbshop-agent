package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.sync.MarketFieldSyncService;
import com.sbshop.agent.core.domain.market.sync.MarketFieldAttempt;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/market-field-sync")
public class MarketFieldSyncController {
	private final MarketFieldSyncService service;

	public record Preview(List<Long> productIds, Set<MarketType> markets, Set<String> fields) {
	}
	public record Commit(boolean acceptApproval) {
	}

	@PostMapping("/reviews")
	public MarketFieldSyncService.Review preview(@RequestBody
	Preview request, Principal actor) {
		return service.preview(request.productIds(), request.markets(), request.fields(), actor.getName());
	}

	@PostMapping("/reviews/{id}/commit")
	public MarketFieldSyncService.Review commit(@PathVariable
	String id, @RequestBody
	Commit request, Principal actor) {
		return service.commit(id, request.acceptApproval(), actor.getName());
	}

	@GetMapping("/reviews/{id}")
	public MarketFieldSyncService.Review get(@PathVariable
	String id, Principal actor) {
		return service.get(id, actor.getName());
	}

	@GetMapping("/reviews")
	public List<MarketFieldSyncService.Review> recent(Principal actor) {
		return service.recent(actor.getName());
	}

	@GetMapping("/tasks/{id}/history")
	public List<MarketFieldAttempt> history(@PathVariable
	Long id, Principal actor) {
		return service.history(id, actor.getName());
	}
}

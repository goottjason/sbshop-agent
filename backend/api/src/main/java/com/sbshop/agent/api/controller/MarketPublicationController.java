package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.sync.MarketPublicationService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.security.Principal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/registrations")
public class MarketPublicationController {
	private final MarketPublicationService service;

	public record Candidates(List<Long> productIds, Set<MarketType> markets) {
	}
	public record Prepare(List<MarketPublicationService.Pair> selected) {
	}
	public record Recheck(String listingId) {
	}

	@PostMapping("/candidates")
	public List<MarketPublicationService.Candidate> candidates(@RequestBody
	Candidates request) {
		return service.candidates(request.productIds(), request.markets());
	}

	@PostMapping("/reviews")
	public MarketPublicationService.PreparedResult prepare(@RequestBody
	Prepare request, Principal actor) {
		return service.prepare(request.selected(), actor.getName());
	}

	@PostMapping("/reviews/{id}/commit")
	public MarketPublicationService.View commit(@PathVariable
	String id, Principal actor) {
		return service.commit(id, actor.getName());
	}

	@GetMapping
	public List<MarketPublicationService.View> recent() {
		return service.recent();
	}

	@GetMapping("/{id}")
	public MarketPublicationService.View get(@PathVariable
	String id) {
		return service.get(id);
	}

	@PostMapping("/{id}/recheck")
	public MarketPublicationService.View recheck(@PathVariable
	String id, @RequestBody
	Recheck request, Principal actor) {
		return service.recheck(id, request.listingId(), actor.getName());
	}
}

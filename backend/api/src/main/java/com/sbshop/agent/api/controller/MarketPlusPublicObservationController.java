package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.marketplus.MarketPlusPublicObservationService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{productId}/marketplus-public-observations")
public class MarketPlusPublicObservationController {
	private final MarketPlusPublicObservationService service;

	@GetMapping("/context")
	public List<MarketPlusPublicObservationService.Target> context(@PathVariable
	Long productId) {
		return service.context(productId);
	}

	@GetMapping
	public List<MarketPlusPublicObservationService.Item> history(@PathVariable
	Long productId) {
		return service.history(productId);
	}

	@PostMapping
	public MarketPlusPublicObservationService.Result ingest(@PathVariable
	Long productId,
		@RequestBody
		MarketPlusPublicObservationService.Request request, Principal actor) {
		return service.ingest(productId, request, actor.getName());
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalid() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "공개 상품 관측의 필수값·형식을 확인하세요."));
	}
}

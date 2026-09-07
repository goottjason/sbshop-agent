package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.inspection.MarketInspectionService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/connection-inspections")
public class MarketInspectionController {
	private final MarketInspectionService service;

	public record Create(List<Long> productIds, String requestId,
		com.sbshop.agent.core.domain.order.enums.MarketType market) {
	}
	public record Retry(String requestId) {
	}

	@GetMapping("/availability")
	public MarketInspectionService.Availability availability(@RequestParam(required = false)
	com.sbshop.agent.core.domain.order.enums.MarketType market) {
		return market == null ? service.availability() : service.availability(market);
	}

	@GetMapping
	public List<MarketInspectionService.BatchView> recent() {
		return service.recent();
	}

	@GetMapping("/{id}")
	public MarketInspectionService.BatchView get(@PathVariable
	String id) {
		return service.get(id);
	}

	@PostMapping
	public MarketInspectionService.BatchView create(@RequestBody
	Create request, Principal actor) {
		return request.market() == null ? service.create(request.productIds(), request.requestId(), actor.getName())
			: service.create(request.productIds(), request.requestId(), actor.getName(), request.market());
	}

	@PostMapping("/{id}/retry")
	public MarketInspectionService.BatchView retry(@PathVariable
	String id, @RequestBody
	Retry request, Principal actor) {
		return service.retry(id, request.requestId(), actor.getName());
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidBody() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "상품 목록과 요청 ID를 확인하세요."));
	}
}

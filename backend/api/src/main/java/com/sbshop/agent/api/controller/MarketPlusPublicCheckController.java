package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.marketplus.MarketPlusPublicCheckService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/marketplus-public-checks")
public class MarketPlusPublicCheckController {
	private final MarketPlusPublicCheckService service;

	@PostMapping("/collections")
	public MarketPlusPublicCheckService.Collection create(@RequestBody
	MarketPlusPublicCheckService.Request request, Principal actor) {
		return service.create(request, actor.getName());
	}

	@GetMapping("/collections")
	public List<MarketPlusPublicCheckService.Collection> recent(Principal actor) {
		return service.recent(actor.getName());
	}

	@GetMapping("/collections/{id}")
	public MarketPlusPublicCheckService.Collection get(@PathVariable
	String id, Principal actor) {
		return service.get(id, actor.getName());
	}

	@PostMapping("/worker/claim")
	public MarketPlusPublicCheckService.ClaimResponse claim(Principal actor) {
		return service.claim(actor.getName());
	}

	@PostMapping("/worker/{id}/report")
	public MarketPlusPublicCheckService.Item report(@PathVariable
	Long id, @RequestBody
	MarketPlusPublicCheckService.Report report, Principal actor) {
		return service.report(id, report, actor.getName());
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalid() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "공개가격 조회 요청의 필수값·형식을 확인하세요."));
	}
}

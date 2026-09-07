package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.marketplus.MarketPlusTransmissionService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MarketPlusTransmissionController {
	private final MarketPlusTransmissionService service;

	@GetMapping("/marketplus/transmissions/readiness")
	public MarketPlusTransmissionService.Readiness readiness() {
		return service.readiness();
	}

	@PostMapping("/marketplus/transmissions/import")
	public MarketPlusTransmissionService.ImportResult ingest(@RequestBody
	MarketPlusTransmissionService.Batch batch, Principal actor) {
		return service.ingest(batch, actor.getName());
	}

	@GetMapping("/products/{productId}/marketplus-transmissions")
	public List<MarketPlusTransmissionService.HistoryItem> history(@PathVariable
	Long productId) {
		return service.history(productId);
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidBody() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "전송 이력 파일 형식과 값을 확인하세요."));
	}
}

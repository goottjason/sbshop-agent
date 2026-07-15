package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.MarketRegistrationService;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/products/{productId}/markets")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MarketRegistrationController {

	private final MarketRegistrationService marketRegistrationService;

	@GetMapping
	public ResponseEntity<List<MarketRegistration>> getMarketRegistrations(@PathVariable
	Long productId) {
		return ResponseEntity.ok(marketRegistrationService.getRegistrations(productId));
	}

	@GetMapping("/{marketType}/local")
	public ResponseEntity<MarketRegistration> getLocalMarketData(
		@PathVariable
		Long productId,
		@PathVariable
		String marketType) {
		return ResponseEntity.ok(marketRegistrationService.getLocalData(productId, marketType));
	}

	@PostMapping("/{marketType}/sync")
	public ResponseEntity<MarketItemInfo> syncMarketLive(
		@PathVariable
		Long productId,
		@PathVariable
		String marketType) {
		return ResponseEntity.ok(marketRegistrationService.syncMarketLive(productId, marketType));
	}
}

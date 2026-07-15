package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.market.MarketRegistrationResponse;
import com.sbshop.agent.core.application.market.MarketRegistrationService;
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
	public ResponseEntity<List<MarketRegistrationResponse>> getMarketRegistrations(@PathVariable
	Long productId) {
		List<MarketRegistrationResponse> registrations =
			marketRegistrationService.getRegistrations(productId).stream()
				.map(MarketRegistrationResponse::from)
				.toList();
		return ResponseEntity.ok(registrations);
	}

	@GetMapping("/{marketType}/local")
	public ResponseEntity<MarketRegistrationResponse> getLocalMarketData(
		@PathVariable
		Long productId,
		@PathVariable
		String marketType) {
		MarketRegistrationResponse response =
			MarketRegistrationResponse.from(marketRegistrationService.getLocalData(productId, marketType));
		return ResponseEntity.ok(response);
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

package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.MarketCredentialService;
import com.sbshop.agent.core.application.market.dto.MarketCredentialDto;
import com.sbshop.agent.core.application.market.dto.MarketCredentialSaveCommand;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-credentials")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // For local Vite frontend
public class MarketCredentialController {

	private final MarketCredentialService marketCredentialService;

	@GetMapping
	public ResponseEntity<List<MarketCredentialDto>> getAllCredentials() {
		return ResponseEntity.ok(marketCredentialService.getAllCredentials());
	}

	@GetMapping("/{marketType}")
	public ResponseEntity<MarketCredentialDto> getCredential(@PathVariable
	MarketType marketType) {
		MarketCredentialDto dto = marketCredentialService.getCredential(marketType);
		return dto != null ? ResponseEntity.ok(dto) : ResponseEntity.notFound().build();
	}

	@PutMapping("/{marketType}")
	public ResponseEntity<MarketCredentialDto> saveCredential(
		@PathVariable
		MarketType marketType, @RequestBody
		MarketCredentialSaveCommand command) {
		command.setMarketType(marketType);
		return ResponseEntity.ok(marketCredentialService.saveCredential(command));
	}
}

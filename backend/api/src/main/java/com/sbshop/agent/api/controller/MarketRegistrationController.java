package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
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

	private final MarketRegistrationRepository marketRegistrationRepository;
	private final MarketClientRouter marketClientRouter;

	@GetMapping
	public ResponseEntity<List<MarketRegistration>> getMarketRegistrations(@PathVariable
	Long productId) {
		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		return ResponseEntity.ok(registrations);
	}

	@GetMapping("/{marketType}/local")
	public ResponseEntity<MarketRegistration> getLocalMarketData(
		@PathVariable
		Long productId,
		@PathVariable
		String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		MarketRegistration reg = marketRegistrationRepository
			.findByProductIdAndMarketType(productId, type)
			.orElseThrow(() -> new IllegalArgumentException("마켓 등록 정보 없음: " + marketType));
		return ResponseEntity.ok(reg);
	}

	@PostMapping("/{marketType}/sync")
	public ResponseEntity<MarketItemInfo> syncMarketLive(
		@PathVariable
		Long productId,
		@PathVariable
		String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		MarketRegistration reg = marketRegistrationRepository
			.findByProductIdAndMarketType(productId, type)
			.orElseThrow(() -> new IllegalArgumentException("마켓 등록 정보 없음: " + marketType));

		String marketItemId = reg.extractVendorItemId();
		if (marketItemId == null || marketItemId.isEmpty()) {
			marketItemId = String.valueOf(reg.getProductId());
		}

		MarketClient client = marketClientRouter.getClient(type);
		MarketItemInfo info = client.extractMarketItem(marketItemId);
		return ResponseEntity.ok(info);
	}
}

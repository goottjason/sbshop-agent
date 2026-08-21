package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.market.MarketPlusHandoffResponse;
import com.sbshop.agent.api.dto.market.MarketRegistrationResponse;
import com.sbshop.agent.core.application.market.MarketRegistrationService;
import com.sbshop.agent.core.application.product.MarketPlusHandoffService;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
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

	private final MarketRegistrationService marketRegistrationService;
	private final MarketPlusHandoffService marketPlusHandoffService;

	@GetMapping
	public ResponseEntity<List<MarketRegistrationResponse>> getMarketRegistrations(@PathVariable
	Long productId) {
		List<MarketRegistrationResponse> registrations = marketRegistrationService.getRegistrations(productId).stream()
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
		MarketRegistrationResponse response = MarketRegistrationResponse
			.from(marketRegistrationService.getLocalData(productId, marketType));
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

	/**
	 * G마켓·옥션은 상품등록 API가 없어 자동 등록할 수 없다. 사용자가 마켓플러스에서 직접 전송하도록
	 * 필요한 정보(상품코드·URL·안내)를 돌려준다. 여기서 아무것도 저장하지 않는다 —
	 * 사용자가 실제로 전송했는지 서버는 알 수 없고, 모르는 것을 등록됨으로 기록하면 배지가 거짓말을 한다.
	 */
	@GetMapping("/{marketType}/handoff")
	public ResponseEntity<MarketPlusHandoffResponse> getMarketPlusHandoff(
		@PathVariable
		Long productId,
		@PathVariable
		String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		return ResponseEntity.ok(MarketPlusHandoffResponse.from(
			marketPlusHandoffService.resolve(productId, type), type.getLabel()));
	}
}

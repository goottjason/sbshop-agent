package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.MarketConnectionService;
import com.sbshop.agent.core.domain.market.MarketConnectionEvent;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/{productId}/connections")
public class MarketConnectionController {
	private final MarketConnectionService service;

	public record Prohibition(Long expectedRevision, String externalId, String sellerAccount, String reason,
		Boolean confirmedPermanent) {
		public Prohibition {
			if (expectedRevision == null || expectedRevision < 0 || externalId == null || externalId.isBlank()
				|| externalId.length() > 200 || !Boolean.TRUE.equals(confirmedPermanent))
				throw new IllegalArgumentException("상품번호·연결 버전과 영구 판매금지 확인이 필요합니다.");
		}
	}

	@GetMapping
	public List<MarketConnectionService.Connection> connections(@PathVariable
	Long productId) {
		return service.connections(productId);
	}

	@GetMapping("/history")
	public List<MarketConnectionEvent> history(@PathVariable
	Long productId) {
		return service.history(productId);
	}

	@PostMapping("/{registrationId}/{market}/inspect")
	public MarketConnectionService.Result inspect(@PathVariable
	Long productId, @PathVariable
	Long registrationId,
		@PathVariable
		MarketType market, Principal actor) {
		return service.inspect(productId, registrationId, market, actor.getName());
	}

	@PostMapping("/{registrationId}/{market}/prohibition")
	public MarketConnectionService.Result prohibit(@PathVariable
	Long productId, @PathVariable
	Long registrationId,
		@PathVariable
		MarketType market, @RequestBody
		Prohibition request, Principal actor) {
		return service.confirmProhibition(productId, registrationId, market, request.expectedRevision(),
			request.externalId(),
			request.sellerAccount(), request.reason(), actor.getName());
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidBody() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "상품번호·연결 버전·금지 확인 값을 확인하세요."));
	}
}

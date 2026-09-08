package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.sync.MarketPublicationInputsService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class MarketPublicationInputsController {
	private final MarketPublicationInputsService service;

	@GetMapping("/{id}/publication-inputs")
	public MarketPublicationInputsService.Inputs inputs(@PathVariable
	Long id, @RequestParam
	MarketType market,
		@RequestParam(required = false)
		String categoryId) {
		return service.inputs(id, market, categoryId);
	}

	public record ElevenstInputReview(Long productRevision,
		String accountReference,
		com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext context) {
	}

	@PostMapping("/{id}/publication-inputs/elevenst-review")
	public java.util.Map<String, Object> reviewElevenst(@PathVariable Long id,
		@RequestBody ElevenstInputReview body) {
		if (body == null || body.productRevision() == null)
			throw new IllegalArgumentException("현재 상품 revision이 필요합니다.");
		return service.reviewElevenstInputs(id, body.productRevision(), body.accountReference(), body.context());
	}
}

package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.source.ProductSourceService;
import com.sbshop.agent.core.application.product.edit.ProductEditService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/source-refresh")
public class ProductSourceRefreshController {
	private final ProductSourceService content;

	public record CommitRequest(String reviewId) {
		public CommitRequest {
			ProductSourceService.requireUuid(reviewId);
		}
	}

	@PostMapping("/collections")
	public ProductSourceService.Collection collect(@RequestBody
	ProductSourceService.CollectionRequest request, Principal actor) {
		return content.collect(request, actor.getName());
	}

	@GetMapping("/collections/{id}")
	public ProductSourceService.Collection collection(@PathVariable
	String id, Principal actor) {
		return content.collection(id, actor.getName());
	}

	@GetMapping("/{productId}/history")
	public List<ProductSourceService.Snapshot> history(@PathVariable
	Long productId, Principal actor) {
		return content.history(productId, actor.getName());
	}

	@PostMapping("/reviews")
	public ProductEditService.Review review(@RequestBody
	ProductSourceService.ReviewRequest request, Principal actor) {
		return content.review(request, actor.getName());
	}

	@PostMapping("/commit")
	public ProductEditService.CommitResult commit(@RequestBody
	CommitRequest request, Principal actor) {
		return content.commit(request.reviewId(), actor.getName());
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidBody() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "수집 요청·선택 항목·검토 ID의 형식을 확인하세요."));
	}
}

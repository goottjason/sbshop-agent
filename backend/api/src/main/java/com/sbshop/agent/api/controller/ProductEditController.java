package com.sbshop.agent.api.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.application.product.edit.ProductEditService;
import com.sbshop.agent.core.application.product.edit.ProductBulkValuesRequest;
import com.sbshop.agent.core.application.product.edit.ProductNumericPreviewUseCase;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductEditController {
	private final ProductEditService edits;

	public record SingleRequest(Long expectedRevision, ObjectNode values) {
		public SingleRequest {
			if (expectedRevision == null || expectedRevision < 0 || values == null)
				throw new IllegalArgumentException("검토할 상품 버전과 변경값이 필요합니다.");
		}
	}
	public record CommitRequest(String reviewId) {
		public CommitRequest {
			if (reviewId == null || reviewId.length() != 36)
				throw new IllegalArgumentException("검토 기록 ID가 필요합니다.");
		}
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidBody() {
		return org.springframework.http.ResponseEntity.badRequest()
			.body(java.util.Map.of("message", "상품 버전·검토 ID·변경값의 형식을 확인하세요."));
	}

	@GetMapping("/{id}/edit-workspace")
	public ProductEditService.Workspace workspace(@PathVariable
	Long id) {
		return edits.workspace(id);
	}

	@GetMapping("/{id}/change-history")
	public List<ProductEditService.History> history(@PathVariable
	Long id) {
		return edits.history(id);
	}

	@PostMapping("/{id}/changes/preview")
	public ProductEditService.Review preview(@PathVariable
	Long id, @RequestBody
	SingleRequest request, Principal actor) {
		return edits.previewSingle(id, request.expectedRevision(), request.values(), actor.getName());
	}

	@PostMapping("/changes/preview")
	public ProductEditService.Review numeric(@RequestBody
	ProductNumericPreviewUseCase.Request request, Principal actor) {
		return edits.previewNumeric(request, actor.getName());
	}

	@GetMapping("/changes/values-preview/fields")
	public List<ProductBulkValuesRequest.Field> valueFields() {
		return ProductBulkValuesRequest.fields();
	}

	@PostMapping("/changes/values-preview")
	public ProductEditService.Review values(@RequestBody
	ProductBulkValuesRequest request, Principal actor) {
		return edits.previewValues(request, actor.getName());
	}

	@PostMapping("/changes/commit")
	public ProductEditService.CommitResult commit(@RequestBody
	CommitRequest request, Principal actor) {
		return edits.commit(request.reviewId(), actor.getName());
	}
}

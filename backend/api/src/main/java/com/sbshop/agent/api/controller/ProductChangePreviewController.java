package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.edit.ProductNumericPreviewUseCase;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products/changes/numeric-preview")
public class ProductChangePreviewController {
	private final ProductNumericPreviewUseCase preview;

	@GetMapping("/fields")
	public List<ProductNumericPreviewUseCase.FieldOption> fields() {
		return preview.fields();
	}

	@PostMapping
	public ProductNumericPreviewUseCase.Response preview(@RequestBody
	ProductNumericPreviewUseCase.Request request) {
		return preview.preview(request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> invalidRequest(HttpMessageNotReadableException exception) {
		return ResponseEntity.badRequest().body(Map.of("message", "상품 선택, 변경 필드·방식, 숫자값의 형식을 확인하세요."));
	}
}

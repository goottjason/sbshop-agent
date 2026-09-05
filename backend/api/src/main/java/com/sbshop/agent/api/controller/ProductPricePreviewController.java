package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.ProductPricePreviewUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductPricePreviewController {
	private final ProductPricePreviewUseCase preview;

	@GetMapping("/{id}/price-preview")
	public ProductPricePreviewUseCase.Response preview(@PathVariable
	Long id) {
		return preview.preview(id);
	}
}

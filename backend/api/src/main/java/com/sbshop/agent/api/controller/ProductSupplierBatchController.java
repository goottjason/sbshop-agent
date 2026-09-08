package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/** Supplier-wide, explicitly configured durable work; product-by-product approval is not required. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/supplier-batches")
public class ProductSupplierBatchController {
	private final ProductSupplierBatchService service;

	@GetMapping("/options")
	public ProductSupplierBatchService.Options options() {
		return service.options();
	}

	@PostMapping
	public ProductSupplierBatchService.View create(@RequestBody
	ProductSupplierBatchService.CreateRequest request,
		Principal actor) {
		return service.create(request, actor.getName());
	}

	@GetMapping
	public Page<ProductSupplierBatchService.View> recent(@RequestParam(defaultValue = "0")
	int page,
		@RequestParam(defaultValue = "20")
		int size) {
		return service.recent(page, size);
	}

	@GetMapping("/{id}")
	public ProductSupplierBatchService.View get(@PathVariable
	String id) {
		return service.get(id);
	}

	@GetMapping("/{id}/items")
	public Page<ProductSupplierBatchService.Item> items(@PathVariable
	String id,
		@RequestParam(defaultValue = "0")
		int page, @RequestParam(defaultValue = "50")
		int size,
		@RequestParam(defaultValue = "")
		String keyword, @RequestParam(defaultValue = "ALL")
		String filter) {
		return service.items(id, page, size, keyword, filter);
	}

	@GetMapping("/{id}/items/{itemId}")
	public ProductSupplierBatchService.ItemDetail detail(@PathVariable
	String id, @PathVariable
	Long itemId) {
		return service.detail(id, itemId);
	}

	@GetMapping("/{id}/retry-options")
	public ProductSupplierBatchService.RetryOptions retryOptions(@PathVariable
	String id) {
		return service.retryOptions(id);
	}

	@PostMapping("/{id}/pause")
	public ProductSupplierBatchService.View pause(@PathVariable
	String id, Principal actor) {
		return service.pause(id, actor.getName());
	}

	@PostMapping("/{id}/resume")
	public ProductSupplierBatchService.View resume(@PathVariable
	String id, Principal actor) {
		return service.resume(id, actor.getName());
	}

	@PostMapping("/{id}/retry")
	public ProductSupplierBatchService.View retry(@PathVariable
	String id,
		@RequestBody
		ProductSupplierBatchService.RetryRequest request, Principal actor) {
		return service.retry(id, request, actor.getName());
	}
}

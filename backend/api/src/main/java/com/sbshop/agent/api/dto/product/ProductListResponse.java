package com.sbshop.agent.api.dto.product;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;

public record ProductListResponse(
		Long id,
		String sbCode,
		String brand,
		String productName,
		String baseName,
		String originalName,
		ProductCategory category,
		VendorType vendor,
		BigDecimal salePrice,
		Integer stock,
		String repImageUrl,
		List<String> hostedImages,
		String sourcingUrl,
		String memo) {

	public static ProductListResponse from(Product p) {
		return new ProductListResponse(
				p.getId(),
				p.getSbCode(),
				p.getBrand(),
				p.getProductName(),
				p.getBaseName(),
				p.getOriginalName(),
				p.getCategory(),
				p.getVendor(),
				p.getSalePrice(),
				p.getStock(),
				p.getRepImageUrl(),
				p.getHostedImages(),
				p.getSourcingUrl(),
				p.getMemo());
	}
}

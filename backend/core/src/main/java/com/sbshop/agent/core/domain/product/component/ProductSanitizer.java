package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import org.springframework.stereotype.Component;

@Component
public class ProductSanitizer {
	public Product sanitizeForPublish(Product product) {
		if (product.getProductName() != null) {
			String sanitized = product.getProductName().replaceAll("[<>\"'&]", "").trim();
			if (!sanitized.equals(product.getProductName())) {
				product.update(ProductUpdateCommand.builder().name(sanitized).build());
			}
		}
		if (product.getBrand() != null) {
			String brand = product.getBrand().replaceAll("[<>\"'&]", "").trim();
			if (!brand.equals(product.getBrand())) {
				product.update(ProductUpdateCommand.builder().brand(brand).build());
			}
		}
		return product;
	}
}

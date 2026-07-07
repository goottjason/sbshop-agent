package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import org.springframework.stereotype.Component;

@Component
public class ProductSanitizer {

	public Product sanitizeForPublish(Product product) {
		if (product.getProductName() != null) {
			String sanitized = product.getProductName().replaceAll("[<>\"'&]", "").trim();
			if (!sanitized.equals(product.getProductName())) {
				product.update(new com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand(
					null, sanitized, null, null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, null, null, null,
					null, null, null, null, null));
			}
		}
		if (product.getBrand() != null) {
			String brand = product.getBrand().replaceAll("[<>\"'&]", "").trim();
			if (!brand.equals(product.getBrand())) {
				product.update(new com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand(
					brand, null, null, null, null, null, null, null, null, null,
					null, null, null, null, null, null, null, null, null, null, null,
					null, null, null, null, null));
			}
		}
		return product;
	}
}
